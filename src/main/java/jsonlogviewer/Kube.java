package jsonlogviewer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Aufrufe von kubectl bzw. oc für Extras → Connect. Zugangsdaten kommen ausschließlich aus der kubeconfig;
 * beswinged übergibt nur --kubeconfig und --context.
 */
final class Kube {

    /** Arten von Zielen; außer Pod werden alle Pods über den Label-Selector des Workloads gelesen. */
    static final List<String> KINDS = List.of("Pod", "Deployment", "ReplicaSet", "StatefulSet", "DaemonSet", "DeploymentConfig");

    record Target(String name, String status, Map<String, String> selector, List<String> containers) {
        @Override public String toString() { return name; }
    }

    private static final int TIMEOUT_SECONDS = 30;
    private final Settings.Cluster cluster;

    Kube(Settings.Cluster cluster) { this.cluster = cluster; }

    Settings.Cluster cluster() { return cluster; }

    String exe() {
        return cluster.toolPath().isBlank() ? cluster.tool() : cluster.toolPath();
    }

    /** Programm plus --kubeconfig/--context. */
    List<String> base(boolean withContext) {
        List<String> l = new ArrayList<>();
        l.add(exe());
        if (!cluster.kubeconfig().isBlank()) { l.add("--kubeconfig"); l.add(cluster.kubeconfig()); }
        if (withContext && !cluster.context().isBlank()) { l.add("--context"); l.add(cluster.context()); }
        return l;
    }

    // ------------------------------------------------------------------ Abfragen

    List<String> contexts() throws IOException {
        List<String> cmd = base(false);
        cmd.addAll(List.of("config", "get-contexts", "-o", "name"));
        return lines(run(cmd));
    }

    List<String> namespaces() throws IOException {
        IOException first = null;
        if (cluster.isOc()) {
            try {
                return names(run(cmd("get", "projects", "-o", "name")));
            } catch (IOException ex) {
                first = ex;
            }
        }
        try {
            return names(run(cmd("get", "namespaces", "-o", "name")));
        } catch (IOException ex) {
            throw first != null ? first : ex;
        }
    }

    /** Pods oder Workloads einer Art im Namespace, mit Status, Selector und Containern. */
    List<Target> targets(String kind, String namespace) throws IOException {
        Object root = Json.parse(run(cmd("get", kind.toLowerCase(java.util.Locale.ROOT), "-n", namespace, "-o", "json")));
        List<Target> out = new ArrayList<>();
        if (!(root instanceof Map<?, ?> m) || !(m.get("items") instanceof List<?> items)) return out;
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> it)) continue;
            String name = str(path(it, "metadata", "name"));
            if (name.isEmpty()) continue;
            Object spec = it.get("spec");
            String status;
            List<String> containers;
            Map<String, String> selector = new LinkedHashMap<>();
            if (kind.equals("Pod")) {
                status = podStatus(it);
                containers = containerNames(spec);
            } else {
                Object sel = path(spec, "selector", "matchLabels");
                if (sel == null && kind.equals("DeploymentConfig")) sel = path(spec, "selector");
                if (sel instanceof Map<?, ?> sm) for (Map.Entry<?, ?> e : sm.entrySet()) selector.put(str(e.getKey()), str(e.getValue()));
                containers = containerNames(path(spec, "template", "spec"));
                Object ready = path(it, "status", "readyReplicas"), want = path(spec, "replicas");
                status = want == null ? "" : (ready == null ? "0" : str(ready)) + "/" + str(want) + " bereit";
                if (kind.equals("DaemonSet")) {
                    Object r = path(it, "status", "numberReady"), d = path(it, "status", "desiredNumberScheduled");
                    status = (r == null ? "0" : str(r)) + "/" + str(d) + " bereit";
                }
            }
            out.add(new Target(name, status, selector, containers));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** Server-Version und ob Logs im Namespace gelesen werden dürfen. */
    String test(String namespace) throws IOException {
        String server = "";
        try {
            Object v = Json.parse(run(cmd("version", "-o", "json", "--request-timeout=10s")));
            server = str(path(v, "serverVersion", "gitVersion"));
        } catch (Json.ParseException ex) {
            // ältere oc-Versionen: kein JSON
        }
        List<String> can = cmd("auth", "can-i", "get", "pods", "--subresource=log");
        if (!namespace.isBlank()) { can.add("-n"); can.add(namespace); }
        String answer;
        try {
            answer = run(can).trim();
        } catch (IOException ex) {
            answer = ex.getMessage(); // "no" kommt mit Exit-Code 1
        }
        return "Verbunden" + (server.isEmpty() ? "" : ", Server " + server)
                + ". Logs lesen" + (namespace.isBlank() ? "" : " in " + namespace) + ": "
                + (answer.startsWith("yes") ? "erlaubt" : answer.startsWith("no") ? "nicht erlaubt" : answer);
    }

    // ------------------------------------------------------------------ Objekte (Show Namespace)

    /** {@code get <arten> -n <ns> -o json}; mehrere Arten durch Komma getrennt. */
    String getJson(String kinds, String namespace) throws IOException {
        return run(cmd("get", kinds, "-n", namespace, "-o", "json"));
    }

    /** Ein Objekt als JSON. */
    String getJson(String kind, String name, String namespace) throws IOException {
        return run(cmd("get", kind.toLowerCase(java.util.Locale.ROOT), name, "-n", namespace, "-o", "json"));
    }

    /** Events zu einem Objekt (nach UID). */
    String eventsJson(String namespace, String uid) throws IOException {
        return run(cmd("get", "events", "-n", namespace, "--field-selector", "involvedObject.uid=" + uid, "-o", "json"));
    }

    // ------------------------------------------------------------------ Log-Befehl

    /**
     * Befehl für {@code logs}. Workloads werden über ihren Label-Selector gelesen (alle Pods, Zeilen gemischt);
     * ohne Selector über {@code <art>/<name>} (dann ein Pod).
     */
    List<String> logsCommand(String namespace, String kind, String name, Map<String, String> selector, String container,
                             String since, int tailLines, boolean previous, boolean follow) {
        List<String> c = base(true);
        c.add("logs");
        c.add("-n");
        c.add(namespace);
        boolean multi = false;
        if (kind.equals("Pod")) {
            c.add(name);
        } else if (selector != null && !selector.isEmpty()) {
            StringJoiner j = new StringJoiner(",");
            selector.forEach((k, v) -> j.add(k + "=" + v));
            c.add("-l");
            c.add(j.toString());
            c.add("--max-log-requests=50");
            multi = true;
        } else {
            c.add(kind.toLowerCase(java.util.Locale.ROOT) + "/" + name);
        }
        if (container == null || container.isBlank()) c.add("--all-containers=true");
        else { c.add("-c"); c.add(container); }
        if (since != null && !since.isBlank()) c.add("--since=" + since);
        if (tailLines > 0) c.add("--tail=" + tailLines);
        else if (multi) c.add("--tail=-1"); // mit -l wären es sonst nur 10 Zeilen je Pod
        if (previous) c.add("--previous");
        if (follow) c.add("-f");
        return c;
    }

    /** Befehl als lesbare Zeile (für die Vorschau im Dialog). */
    static String display(List<String> cmd) {
        StringJoiner j = new StringJoiner(" ");
        for (String s : cmd) j.add(s.isEmpty() || s.matches(".*[\\s\"'].*") ? '"' + s.replace("\"", "\\\"") + '"' : s);
        return j.toString();
    }

    // ------------------------------------------------------------------ laufende Log-Prozesse

    private static final Map<Path, Process> RUNNING = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(Kube::stopAll, "kube-stop"));
    }

    /** Startet den Befehl; stdout geht direkt in {@code out}, stderr in {@code <out>.err.txt}. */
    static Process startLogs(List<String> cmd, Path out) throws IOException {
        stop(out);
        Files.createDirectories(out.getParent());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(out.toFile());
        pb.redirectError(errFile(out).toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(nullFile()));
        Process p;
        try {
            p = pb.start();
        } catch (IOException ex) {
            throw notFound(cmd.get(0), ex);
        }
        RUNNING.put(out.toAbsolutePath().normalize(), p);
        p.onExit().thenRun(() -> RUNNING.remove(out.toAbsolutePath().normalize(), p));
        return p;
    }

    static boolean isRunning(Path out) {
        Process p = RUNNING.get(out.toAbsolutePath().normalize());
        return p != null && p.isAlive();
    }

    static void stop(Path out) {
        Process p = RUNNING.remove(out.toAbsolutePath().normalize());
        if (p != null) destroy(p);
    }

    static void stopAll() {
        for (Process p : RUNNING.values()) destroy(p);
        RUNNING.clear();
    }

    private static void destroy(Process p) {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }

    static Path errFile(Path out) { return out.resolveSibling(out.getFileName() + ".err.txt"); }

    /** Letzte Zeilen der Fehlerausgabe eines Log-Prozesses. */
    static String errText(Path out) {
        try {
            return lastLines(Files.readString(errFile(out), StandardCharsets.UTF_8), 6);
        } catch (IOException ex) {
            return "";
        }
    }

    private static java.io.File nullFile() {
        return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private List<String> cmd(String... args) {
        List<String> c = base(true);
        c.addAll(List.of(args));
        return c;
    }

    /** Führt den Befehl aus und liefert stdout; bei Fehler IOException mit stderr. */
    static String run(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectInput(ProcessBuilder.Redirect.from(nullFile()));
        Process p;
        try {
            p = pb.start();
        } catch (IOException ex) {
            throw notFound(cmd.get(0), ex);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        Thread to = pump(p.getInputStream(), out), te = pump(p.getErrorStream(), err);
        try {
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                destroy(p);
                throw new IOException("Zeitüberschreitung nach " + TIMEOUT_SECONDS + " s: " + display(cmd)
                        + "\n(Fragt die Anmeldung interaktiv nach? Dann einmal im Terminal anmelden.)");
            }
            to.join(5000);
            te.join(5000);
        } catch (InterruptedException ex) {
            destroy(p);
            Thread.currentThread().interrupt();
            throw new IOException("abgebrochen");
        }
        String stdout = out.toString(StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            String msg = lastLines(err.toString(StandardCharsets.UTF_8), 6);
            if (msg.isBlank()) msg = lastLines(stdout, 3);
            throw new IOException(msg.isBlank() ? "Exit-Code " + p.exitValue() : msg);
        }
        return stdout;
    }

    private static IOException notFound(String exe, IOException ex) {
        return new IOException("„" + exe + "“ konnte nicht gestartet werden. Ist es installiert und im PATH? "
                + "Sonst den Pfad unter Extras → Connect Settings angeben.\n(" + ex.getMessage() + ")", ex);
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream to) {
        Thread t = new Thread(() -> {
            try (in) {
                in.transferTo(to);
            } catch (IOException ignored) {
                // Prozess beendet
            }
        }, "kube-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static List<String> lines(String s) {
        List<String> l = new ArrayList<>();
        for (String x : s.split("\\R")) if (!x.isBlank()) l.add(x.trim());
        return l;
    }

    /** "namespace/foo" bzw. "project.project.openshift.io/foo" -> "foo". */
    private static List<String> names(String s) {
        List<String> l = new ArrayList<>();
        for (String x : lines(s)) l.add(x.substring(x.lastIndexOf('/') + 1));
        l.sort(String::compareToIgnoreCase);
        return l;
    }

    private static String lastLines(String s, int n) {
        List<String> l = lines(s);
        return String.join("\n", l.subList(Math.max(0, l.size() - n), l.size()));
    }

    private static List<String> containerNames(Object podSpec) {
        List<String> l = new ArrayList<>();
        if (path(podSpec, "containers") instanceof List<?> cs)
            for (Object c : cs) if (c instanceof Map<?, ?> cm && cm.get("name") != null) l.add(str(cm.get("name")));
        return l;
    }

    private static String podStatus(Map<?, ?> pod) {
        String phase = str(path(pod, "status", "phase"));
        int ready = 0, total = 0, restarts = 0;
        String waiting = "";
        if (path(pod, "status", "containerStatuses") instanceof List<?> cs) {
            for (Object o : cs) {
                if (!(o instanceof Map<?, ?> c)) continue;
                total++;
                if (Boolean.TRUE.equals(c.get("ready"))) ready++;
                try { restarts += Integer.parseInt(str(c.get("restartCount"))); } catch (NumberFormatException ignored) { }
                Object reason = path(c, "state", "waiting", "reason");
                if (reason != null) waiting = str(reason);
            }
        }
        String s = waiting.isEmpty() ? phase : waiting;
        if (total > 0) s += ", " + ready + "/" + total + " bereit";
        if (restarts > 0) s += ", " + restarts + " Neustarts";
        return s;
    }

    static Object path(Object o, String... keys) {
        for (String k : keys) {
            if (!(o instanceof Map<?, ?> m)) return null;
            o = m.get(k);
        }
        return o;
    }

    static String str(Object o) { return o == null ? "" : o.toString(); }
}
