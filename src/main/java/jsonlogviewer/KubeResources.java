package jsonlogviewer;

import static jsonlogviewer.Kube.path;
import static jsonlogviewer.Kube.str;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Objekte eines Namespaces für Extras → Show Namespace: laden, Eltern/Kinder über ownerReferences verknüpfen und den
 * Zustand nach den Regeln von Argo CD bewerten (Healthy, Progressing, Degraded, Suspended, Missing, Unknown).
 */
final class KubeResources {

    /** Abgefragte Arten (Plural, wie in {@code kubectl get}). Unbekannte (z.B. routes ohne OpenShift) werden übersprungen. */
    static final List<String> QUERY = List.of(
            "deployments", "statefulsets", "daemonsets", "replicasets", "pods", "jobs", "cronjobs",
            "services", "endpoints", "ingresses", "configmaps", "secrets", "persistentvolumeclaims", "serviceaccounts",
            "horizontalpodautoscalers", "poddisruptionbudgets", "networkpolicies",
            "deploymentconfigs", "replicationcontrollers", "routes");

    /** Reihenfolge der Kacheln unter dem Namespace und Kurzzeichen im Symbol. */
    private static final List<String> ORDER = List.of(
            "Deployment", "DeploymentConfig", "StatefulSet", "DaemonSet", "CronJob", "Job", "ReplicaSet", "ReplicationController",
            "Pod", "Service", "Endpoints", "Ingress", "Route", "HorizontalPodAutoscaler", "PodDisruptionBudget", "NetworkPolicy",
            "PersistentVolumeClaim", "ConfigMap", "Secret", "ServiceAccount");
    private static final Map<String, String> ABBR = Map.ofEntries(
            Map.entry("Deployment", "deploy"), Map.entry("DeploymentConfig", "dc"), Map.entry("StatefulSet", "sts"),
            Map.entry("DaemonSet", "ds"), Map.entry("CronJob", "cj"), Map.entry("Job", "job"), Map.entry("ReplicaSet", "rs"),
            Map.entry("ReplicationController", "rc"), Map.entry("Pod", "pod"), Map.entry("Service", "svc"),
            Map.entry("Endpoints", "ep"), Map.entry("Ingress", "ing"), Map.entry("Route", "route"),
            Map.entry("HorizontalPodAutoscaler", "hpa"), Map.entry("PodDisruptionBudget", "pdb"),
            Map.entry("NetworkPolicy", "netpol"), Map.entry("PersistentVolumeClaim", "pvc"), Map.entry("ConfigMap", "cm"),
            Map.entry("Secret", "secret"), Map.entry("ServiceAccount", "sa"), Map.entry("Namespace", "ns"));

    enum Health {
        // Reihenfolge = „schlechter als" wie in Argo CD
        HEALTHY("Healthy", 0x18BE94), SUSPENDED("Suspended", 0x766F94), PROGRESSING("Progressing", 0x0DADEA),
        MISSING("Missing", 0xF4C030), DEGRADED("Degraded", 0xE96D76), UNKNOWN("Unknown", 0xA3B1BC);

        final String label;
        final int rgb;

        Health(String label, int rgb) { this.label = label; this.rgb = rgb; }

        static Health worst(Health a, Health b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.ordinal() >= b.ordinal() ? a : b;
        }
    }

    /** Ein Kubernetes-Objekt im Baum. */
    static final class Res {
        final String kind, name, uid;
        final Map<String, Object> obj;
        Health health;           // null = ohne Zustand (ConfigMap, Secret, …)
        String message = "";     // Grund für den Zustand
        String info = "";        // zweite Zeile der Kachel
        int restarts;
        boolean system;          // automatisch angelegt (default-ServiceAccount, kube-root-ca.crt, …)
        Instant created;
        Res parent;
        final List<Res> children = new ArrayList<>();

        Res(String kind, String name, String uid, Map<String, Object> obj) {
            this.kind = kind;
            this.name = name;
            this.uid = uid;
            this.obj = obj;
        }

        String abbr() { return ABBR.getOrDefault(kind, kind.length() > 5 ? kind.substring(0, 5).toLowerCase(Locale.ROOT) : kind.toLowerCase(Locale.ROOT)); }

        boolean isRoot() { return kind.equals("Namespace"); }

        /** Kann mit Extras → Connect gelesen werden. */
        boolean hasLogs() { return Kube.KINDS.contains(kind); }

        String key() { return kind + "/" + name; }

        @Override public String toString() { return key(); }
    }

    /** Ergebnis einer Abfrage. {@code errors}: Art → Meldung (z.B. kein Zugriff). */
    record Snapshot(Res root, List<Res> all, Map<String, String> errors, Instant time) { }

    private final Set<String> unavailable = ConcurrentHashMap.newKeySet();   // Art gibt es auf dem Cluster nicht
    private final Map<String, String> forbidden = new ConcurrentHashMap<>(); // Art → Meldung

    /** Lädt alle Objekte. Erst ein gemeinsamer Aufruf; scheitert er an einzelnen Arten, jede Art für sich. */
    Snapshot load(Kube kube, String namespace) throws IOException {
        List<String> kinds = new ArrayList<>();
        for (String k : QUERY) if (!unavailable.contains(k) && !forbidden.containsKey(k)) kinds.add(k);
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            items.addAll(items(kube.getJson(String.join(",", kinds), namespace)));
        } catch (IOException ex) {
            if (!isPerKindError(ex.getMessage())) throw ex; // z.B. Cluster nicht erreichbar oder Anmeldung abgelaufen
            ExecutorService pool = Executors.newFixedThreadPool(6, r -> { Thread t = new Thread(r, "kube-get"); t.setDaemon(true); return t; });
            try {
                Map<String, Future<List<Map<String, Object>>>> fs = new LinkedHashMap<>();
                for (String k : kinds) fs.put(k, pool.submit(() -> items(kube.getJson(k, namespace))));
                IOException other = null;
                int ok = 0;
                for (Map.Entry<String, Future<List<Map<String, Object>>>> e : fs.entrySet()) {
                    try {
                        items.addAll(e.getValue().get());
                        ok++;
                    } catch (java.util.concurrent.ExecutionException ee) {
                        String msg = ee.getCause() == null ? String.valueOf(ee) : String.valueOf(ee.getCause().getMessage());
                        if (isUnknownType(msg)) unavailable.add(e.getKey());
                        else if (isForbidden(msg)) forbidden.put(e.getKey(), "kein Zugriff");
                        else if (other == null) other = new IOException(msg);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("abgebrochen");
                    }
                }
                if (ok == 0 && other != null) throw other;
            } finally {
                pool.shutdownNow();
            }
        }
        return build(namespace, items, new LinkedHashMap<>(forbidden));
    }

    private static boolean isUnknownType(String msg) {
        return msg != null && (msg.contains("doesn't have a resource type") || msg.contains("could not find the requested resource")
                || msg.contains("no matches for kind"));
    }

    private static boolean isForbidden(String msg) {
        return msg != null && (msg.contains("Forbidden") || msg.contains("forbidden"));
    }

    private static boolean isPerKindError(String msg) { return isUnknownType(msg) || isForbidden(msg); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(String json) {
        List<Map<String, Object>> l = new ArrayList<>();
        Object root = Json.parse(json);
        if (root instanceof Map<?, ?> m && m.get("items") instanceof List<?> its) {
            for (Object o : its) if (o instanceof Map<?, ?> it) l.add((Map<String, Object>) it);
        } else if (root instanceof Map<?, ?> m && m.get("kind") != null) {
            l.add((Map<String, Object>) m);
        }
        return l;
    }

    // ------------------------------------------------------------------ Baum

    static Snapshot build(String namespace, List<Map<String, Object>> items, Map<String, String> errors) {
        Res root = new Res("Namespace", namespace, "", Map.of());
        List<Res> all = new ArrayList<>();
        Map<String, Res> byUid = new HashMap<>();
        Map<String, Res> services = new HashMap<>();
        for (Map<String, Object> it : items) {
            String kind = str(it.get("kind")), name = str(path(it, "metadata", "name")), uid = str(path(it, "metadata", "uid"));
            if (kind.isEmpty() || name.isEmpty() || kind.equals("Event")) continue;
            Res r = new Res(kind, name, uid.isEmpty() ? kind + "/" + name : uid, it);
            try { r.created = Instant.parse(str(path(it, "metadata", "creationTimestamp"))); } catch (Exception ignored) { }
            assess(r);
            r.system = isSystem(r);
            if (byUid.putIfAbsent(r.uid, r) != null) continue; // doppelt (z.B. gleiche Art unter zwei Namen abgefragt)
            all.add(r);
            if (kind.equals("Service")) services.put(name, r);
        }
        for (Res r : all) {
            Res parent = null;
            if (path(r.obj, "metadata", "ownerReferences") instanceof List<?> owners) {
                for (Object o : owners) {
                    Res p = byUid.get(str(path(o, "uid")));
                    if (p != null && p != r) { parent = p; break; }
                }
            }
            if (parent == null && r.kind.equals("Endpoints")) parent = services.get(r.name);
            r.parent = parent != null ? parent : root;
        }
        // Zyklen (sollte es nicht geben) auflösen
        for (Res r : all) {
            Res p = r.parent;
            for (int i = 0; p != null && i < 50; i++) p = p.parent;
            if (p != null) r.parent = root;
        }
        for (Res r : all) r.parent.children.add(r);
        Comparator<Res> cmp = Comparator.<Res>comparingInt(r -> {
            int i = ORDER.indexOf(r.kind);
            return i < 0 ? ORDER.size() : i;
        }).thenComparing((a, b) -> {
            if (a.kind.equals("ReplicaSet") || a.kind.equals("ReplicationController")) return Integer.compare(revision(b), revision(a));
            return 0;
        }).thenComparing(r -> r.name, String.CASE_INSENSITIVE_ORDER);
        sort(root, cmp);
        Health h = null;
        int bad = 0;
        for (Res r : all) {
            h = Health.worst(h, r.health);
            if (r.health == Health.DEGRADED || r.health == Health.MISSING) bad++;
        }
        root.health = h == null ? Health.HEALTHY : h;
        root.info = all.size() + " Objekte" + (bad > 0 ? ", " + bad + " fehlerhaft" : "");
        return new Snapshot(root, Collections.unmodifiableList(all), errors, Instant.now());
    }

    private static void sort(Res r, Comparator<Res> cmp) {
        r.children.sort(cmp);
        for (Res c : r.children) sort(c, cmp);
    }

    private static boolean isSystem(Res r) {
        switch (r.kind) {
            case "ConfigMap":
                return r.name.equals("kube-root-ca.crt") || r.name.equals("openshift-service-ca.crt");
            case "ServiceAccount":
                return r.name.equals("default") || r.name.equals("builder") || r.name.equals("deployer") || r.name.equals("pipeline");
            case "Secret": {
                String type = str(path(r.obj, "type"));
                return type.equals("kubernetes.io/service-account-token")
                        || (type.equals("kubernetes.io/dockercfg") && path(r.obj, "metadata", "annotations", "kubernetes.io/service-account.name") != null);
            }
            case "RoleBinding":
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ Zustand (angelehnt an Argo CD)

    private static final Set<String> BAD_WAITING = Set.of("CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "InvalidImageName",
            "CreateContainerConfigError", "CreateContainerError", "RunContainerError", "ErrImageNeverPull", "ContainerCannotRun");

    static void assess(Res r) {
        Map<String, Object> o = r.obj;
        Object spec = o.get("spec"), st = o.get("status");
        switch (r.kind) {
            case "Pod" -> assessPod(r);
            case "Deployment" -> {
                int want = num(path(spec, "replicas"), 1), upd = num(path(st, "updatedReplicas"), 0),
                        avail = num(path(st, "availableReplicas"), 0), ready = num(path(st, "readyReplicas"), 0);
                r.info = "rev:" + revision(r) + "  " + ready + "/" + want + " bereit";
                if (Boolean.TRUE.equals(path(spec, "paused"))) set(r, Health.SUSPENDED, "Deployment ist pausiert");
                else if (condition(o, "Progressing", "False") != null || "ProgressDeadlineExceeded".equals(conditionReason(o, "Progressing")))
                    set(r, Health.DEGRADED, "Rollout hängt: " + conditionMessage(o, "Progressing"));
                else if (num(path(st, "observedGeneration"), 0) < num(path(o, "metadata", "generation"), 0))
                    set(r, Health.PROGRESSING, "Neue Version wird verarbeitet");
                else if (upd < want) set(r, Health.PROGRESSING, upd + " von " + want + " Pods aktualisiert");
                else if (num(path(st, "replicas"), 0) > upd) set(r, Health.PROGRESSING, "Alte Pods werden beendet");
                else if (avail < upd) set(r, Health.PROGRESSING, avail + " von " + upd + " Pods verfügbar");
                else set(r, Health.HEALTHY, "");
            }
            case "DeploymentConfig" -> {
                int want = num(path(spec, "replicas"), 1), ready = num(path(st, "readyReplicas"), 0),
                        avail = num(path(st, "availableReplicas"), 0);
                r.info = "rev:" + num(path(st, "latestVersion"), 0) + "  " + ready + "/" + want + " bereit";
                if (Boolean.TRUE.equals(path(spec, "paused"))) set(r, Health.SUSPENDED, "pausiert");
                else if ("ProgressDeadlineExceeded".equals(conditionReason(o, "Progressing"))) set(r, Health.DEGRADED, conditionMessage(o, "Progressing"));
                else if (avail < want) set(r, Health.PROGRESSING, avail + " von " + want + " verfügbar");
                else set(r, Health.HEALTHY, "");
            }
            case "StatefulSet" -> {
                int want = num(path(spec, "replicas"), 1), ready = num(path(st, "readyReplicas"), 0), upd = num(path(st, "updatedReplicas"), 0);
                r.info = ready + "/" + want + " bereit";
                if (num(path(st, "observedGeneration"), 0) < num(path(o, "metadata", "generation"), 0))
                    set(r, Health.PROGRESSING, "Neue Version wird verarbeitet");
                else if (ready < want) set(r, Health.PROGRESSING, ready + " von " + want + " Pods bereit");
                else if ("RollingUpdate".equals(str(path(spec, "updateStrategy", "type"))) && upd < want
                        && !str(path(st, "updateRevision")).equals(str(path(st, "currentRevision"))))
                    set(r, Health.PROGRESSING, upd + " von " + want + " Pods aktualisiert");
                else set(r, Health.HEALTHY, "");
            }
            case "DaemonSet" -> {
                int want = num(path(st, "desiredNumberScheduled"), 0), avail = num(path(st, "numberAvailable"), 0),
                        upd = num(path(st, "updatedNumberScheduled"), 0), ready = num(path(st, "numberReady"), 0);
                r.info = ready + "/" + want + " bereit";
                if (num(path(st, "observedGeneration"), 0) < num(path(o, "metadata", "generation"), 0))
                    set(r, Health.PROGRESSING, "Neue Version wird verarbeitet");
                else if (upd < want) set(r, Health.PROGRESSING, upd + " von " + want + " Pods aktualisiert");
                else if (avail < want) set(r, Health.PROGRESSING, avail + " von " + want + " Pods verfügbar");
                else set(r, Health.HEALTHY, "");
            }
            case "ReplicaSet", "ReplicationController" -> {
                int want = num(path(spec, "replicas"), 1), avail = num(path(st, "availableReplicas"), 0), ready = num(path(st, "readyReplicas"), 0);
                r.info = "rev:" + revision(r) + "  " + ready + "/" + want + " bereit";
                if (condition(o, "ReplicaFailure", "True") != null) set(r, Health.DEGRADED, conditionMessage(o, "ReplicaFailure"));
                else if (avail < want) set(r, Health.PROGRESSING, avail + " von " + want + " Pods verfügbar");
                else set(r, Health.HEALTHY, "");
            }
            case "Job" -> {
                int ok = num(path(st, "succeeded"), 0), fail = num(path(st, "failed"), 0), want = num(path(spec, "completions"), 1);
                r.info = ok + "/" + want + " fertig" + (fail > 0 ? ", " + fail + " fehlgeschlagen" : "");
                if (condition(o, "Failed", "True") != null) set(r, Health.DEGRADED, conditionMessage(o, "Failed"));
                else if (condition(o, "Complete", "True") != null) set(r, Health.HEALTHY, "abgeschlossen");
                else if (Boolean.TRUE.equals(path(spec, "suspend"))) set(r, Health.SUSPENDED, "angehalten");
                else set(r, Health.PROGRESSING, "läuft");
            }
            case "CronJob" -> {
                r.info = str(path(spec, "schedule"));
                if (Boolean.TRUE.equals(path(spec, "suspend"))) set(r, Health.SUSPENDED, "angehalten");
                else set(r, Health.HEALTHY, "");
            }
            case "Service" -> {
                String type = str(path(spec, "type"));
                r.info = type.isEmpty() ? "ClusterIP" : type;
                String ip = str(path(spec, "clusterIP"));
                if (!ip.isEmpty() && !ip.equals("None")) r.info += "  " + ip;
                if (type.equals("LoadBalancer") && !(path(st, "loadBalancer", "ingress") instanceof List<?> l && !l.isEmpty()))
                    set(r, Health.PROGRESSING, "Warte auf externe IP");
                else set(r, Health.HEALTHY, "");
            }
            case "Ingress" -> {
                List<String> hosts = new ArrayList<>();
                if (path(spec, "rules") instanceof List<?> rules) for (Object x : rules) if (path(x, "host") != null) hosts.add(str(path(x, "host")));
                r.info = String.join(", ", hosts);
                set(r, Health.HEALTHY, "");
            }
            case "Route" -> {
                r.info = str(path(spec, "host"));
                Health h = Health.PROGRESSING;
                String msg = "Warte auf Freigabe durch den Router";
                if (path(st, "ingress") instanceof List<?> ing) {
                    for (Object i : ing) {
                        if (path(i, "conditions") instanceof List<?> cs) for (Object c : cs) {
                            if ("Admitted".equals(str(path(c, "type")))) {
                                if ("True".equals(str(path(c, "status")))) { h = Health.HEALTHY; msg = ""; }
                                else { h = Health.DEGRADED; msg = str(path(c, "message")); }
                            }
                        }
                    }
                }
                set(r, h, msg);
            }
            case "PersistentVolumeClaim" -> {
                String phase = str(path(st, "phase"));
                r.info = phase + "  " + str(path(st, "capacity", "storage"));
                switch (phase) {
                    case "Bound" -> set(r, Health.HEALTHY, "");
                    case "Lost" -> set(r, Health.DEGRADED, "Volume verloren");
                    default -> set(r, Health.PROGRESSING, "Warte auf Volume");
                }
            }
            case "HorizontalPodAutoscaler" -> {
                r.info = num(path(st, "currentReplicas"), 0) + " Pods (" + num(path(spec, "minReplicas"), 1) + "–" + num(path(spec, "maxReplicas"), 0) + ")";
                if (condition(o, "AbleToScale", "False") != null) set(r, Health.DEGRADED, conditionMessage(o, "AbleToScale"));
                else if (condition(o, "ScalingActive", "False") != null) set(r, Health.DEGRADED, conditionMessage(o, "ScalingActive"));
                else set(r, Health.HEALTHY, "");
            }
            case "PodDisruptionBudget" -> {
                int allowed = num(path(st, "disruptionsAllowed"), 0);
                r.info = allowed + " Unterbrechungen erlaubt";
                int cur = num(path(st, "currentHealthy"), 0), des = num(path(st, "desiredHealthy"), 0);
                if (cur < des) set(r, Health.DEGRADED, cur + " von mindestens " + des + " Pods gesund");
                else set(r, Health.HEALTHY, "");
            }
            case "ConfigMap" -> r.info = count(o.get("data")) + count(o.get("binaryData")) + " Einträge";
            case "Secret" -> r.info = shortType(str(o.get("type"))) + "  " + count(o.get("data")) + " Einträge";
            case "Endpoints" -> {
                int n = 0, nr = 0;
                if (o.get("subsets") instanceof List<?> ss) for (Object s : ss) {
                    if (path(s, "addresses") instanceof List<?> a) n += a.size();
                    if (path(s, "notReadyAddresses") instanceof List<?> a) nr += a.size();
                }
                r.info = n + " Adressen" + (nr > 0 ? ", " + nr + " nicht bereit" : "");
            }
            default -> { }
        }
    }

    private static void assessPod(Res r) {
        Map<String, Object> o = r.obj;
        String phase = str(path(o, "status", "phase"));
        int ready = 0, total = 0, restarts = 0;
        String bad = null, waiting = null, terminated = null;
        List<Object> statuses = new ArrayList<>();
        if (path(o, "status", "initContainerStatuses") instanceof List<?> ic) statuses.addAll(ic);
        int init = statuses.size();
        if (path(o, "status", "containerStatuses") instanceof List<?> cs) statuses.addAll(cs);
        for (int i = 0; i < statuses.size(); i++) {
            Object c = statuses.get(i);
            restarts += num(path(c, "restartCount"), 0);
            String w = str(path(c, "state", "waiting", "reason"));
            if (!w.isEmpty() && !w.equals("PodInitializing") && !w.equals("ContainerCreating")) {
                if (BAD_WAITING.contains(w)) bad = w + ": " + str(path(c, "state", "waiting", "message"));
                else if (waiting == null) waiting = w;
            }
            String t = str(path(c, "state", "terminated", "reason"));
            if (i < init) continue;
            total++;
            if (Boolean.TRUE.equals(path(c, "ready"))) ready++;
            if (!t.isEmpty() && !t.equals("Completed") && terminated == null) terminated = t;
        }
        r.restarts = restarts;
        String shown = bad != null ? bad.substring(0, bad.indexOf(':')) : waiting != null ? waiting : terminated != null ? terminated : phase;
        if (path(o, "metadata", "deletionTimestamp") != null) shown = "Terminating";
        r.info = shown.toLowerCase(Locale.ROOT) + "  " + ready + "/" + total + (restarts > 0 ? "  ↻ " + restarts : "");
        if (path(o, "metadata", "deletionTimestamp") != null) { set(r, Health.PROGRESSING, "Pod wird beendet"); return; }
        if (bad != null) { set(r, Health.DEGRADED, bad.endsWith(": ") ? bad.substring(0, bad.length() - 2) : bad); return; }
        switch (phase) {
            case "Succeeded" -> set(r, Health.HEALTHY, "beendet");
            case "Failed" -> set(r, Health.DEGRADED, or(str(path(o, "status", "message")), or(str(path(o, "status", "reason")), "fehlgeschlagen")));
            case "Pending" -> set(r, Health.PROGRESSING, or(conditionMessage(o, "PodScheduled"), waiting != null ? waiting : "wartet"));
            case "Running" -> {
                String policy = str(path(o, "spec", "restartPolicy"));
                if (ready == total) set(r, Health.HEALTHY, "");
                else if (policy.equals("Never") || policy.equals("OnFailure")) set(r, Health.PROGRESSING, "läuft");
                else set(r, Health.PROGRESSING, (total - ready) + " Container nicht bereit"
                        + (conditionMessage(o, "Ready").isEmpty() ? "" : ": " + conditionMessage(o, "Ready")));
            }
            default -> set(r, Health.UNKNOWN, phase);
        }
    }

    private static void set(Res r, Health h, String msg) {
        r.health = h;
        r.message = msg == null ? "" : msg;
    }

    private static Object condition(Map<String, Object> o, String type, String status) {
        if (path(o, "status", "conditions") instanceof List<?> cs)
            for (Object c : cs) if (type.equals(str(path(c, "type"))) && status.equals(str(path(c, "status")))) return c;
        return null;
    }

    private static Object conditionAny(Map<String, Object> o, String type) {
        if (path(o, "status", "conditions") instanceof List<?> cs)
            for (Object c : cs) if (type.equals(str(path(c, "type")))) return c;
        return null;
    }

    private static String conditionReason(Map<String, Object> o, String type) { return str(path(conditionAny(o, type), "reason")); }

    private static String conditionMessage(Map<String, Object> o, String type) { return str(path(conditionAny(o, type), "message")); }

    static int revision(Res r) {
        Object rev = path(r.obj, "metadata", "annotations", "deployment.kubernetes.io/revision");
        if (rev == null) rev = path(r.obj, "metadata", "annotations", "openshift.io/deployment-config.latest-version");
        return num(rev, 0);
    }

    private static int num(Object o, int def) {
        if (o == null) return def;
        try {
            return (int) Double.parseDouble(o.toString());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static int count(Object o) { return o instanceof Map<?, ?> m ? m.size() : 0; }

    private static String or(String s, String d) { return s == null || s.isEmpty() ? d : s; }

    private static String shortType(String t) {
        return switch (t) {
            case "Opaque", "" -> "Opaque";
            case "kubernetes.io/tls" -> "TLS";
            case "kubernetes.io/dockerconfigjson", "kubernetes.io/dockercfg" -> "Registry";
            case "kubernetes.io/service-account-token" -> "SA-Token";
            case "kubernetes.io/basic-auth" -> "Basic-Auth";
            default -> t.substring(t.lastIndexOf('/') + 1);
        };
    }

    /** Alter wie in kubectl: 45s, 12m, 5h, 3d. */
    static String age(Instant t) {
        if (t == null) return "";
        long s = Math.max(0, Duration.between(t, Instant.now()).getSeconds());
        if (s < 120) return s + "s";
        if (s < 7200) return s / 60 + "m";
        if (s < 48 * 3600) return s / 3600 + "h";
        if (s < 365L * 86400) return s / 86400 + "d";
        return s / (365L * 86400) + "y";
    }
}
