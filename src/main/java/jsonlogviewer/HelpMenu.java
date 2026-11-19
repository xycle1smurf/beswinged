package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.HyperlinkEvent;

/** Menü Hilfe: Hilfetext, Suche nach Updates (GitHub Releases), Über-Dialog. Dazu Version und Programm-Icons. */
final class HelpMenu {

    static final String REPO = "xycle1smurf/beswinged";
    static final String PROJECT_URL = "https://github.com/" + REPO;
    static final String RELEASES_URL = PROJECT_URL + "/releases";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static JDialog helpDialog;

    private HelpMenu() { }

    static JMenu create(JFrame owner) {
        JMenu menu = new JMenu("Hilfe");
        JMenuItem help = new JMenuItem("Hilfe");
        help.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0));
        help.addActionListener(e -> showHelp(owner));
        JMenuItem updates = new JMenuItem("Suche nach Updates…");
        updates.addActionListener(e -> checkForUpdates(owner));
        JMenuItem about = new JMenuItem("Über beswinged");
        about.addActionListener(e -> showAbout(owner));
        menu.add(help);
        menu.add(updates);
        menu.addSeparator();
        menu.add(about);
        return menu;
    }

    private static String version;

    /** Version aus version.properties (von Maven gefüllt, Release-Builds mit -Drevision); sonst "dev". */
    static synchronized String version() {
        if (version == null) {
            String v = null;
            try (InputStream in = HelpMenu.class.getResourceAsStream("version.properties")) {
                if (in != null) {
                    java.util.Properties p = new java.util.Properties();
                    p.load(in);
                    v = p.getProperty("version");
                }
            } catch (IOException ignored) {
                // unten Fallback
            }
            if (v == null || v.isBlank() || v.contains("${")) v = HelpMenu.class.getPackage().getImplementationVersion();
            version = v == null || v.isBlank() ? "dev" : v.trim();
        }
        return version;
    }

    /** Programm-Icon in mehreren Größen für Fenster und Taskleiste. */
    static List<Image> icons() {
        List<Image> list = new ArrayList<>();
        for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            try (InputStream in = HelpMenu.class.getResourceAsStream("icons/icon-" + s + ".png")) {
                if (in != null) list.add(ImageIO.read(in));
            } catch (IOException ignored) {
                // ohne Icon weiter
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ Hilfe

    static void showHelp(JFrame owner) {
        if (helpDialog != null) {
            helpDialog.setVisible(true);
            helpDialog.toFront();
            return;
        }
        JEditorPane pane = new JEditorPane();
        pane.setEditable(false);
        pane.setContentType("text/html");
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        String html;
        try (InputStream in = HelpMenu.class.getResourceAsStream("help.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${version}", version());
        } catch (Exception ex) {
            html = "<html><body><p>Hilfetext nicht gefunden. Siehe <a href=\"" + PROJECT_URL + "\">" + PROJECT_URL + "</a></p></body></html>";
        }
        pane.setText(html);
        pane.setCaretPosition(0);
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            String d = e.getDescription();
            if (d != null && d.startsWith("#")) pane.scrollToReference(d.substring(1));
            else if (e.getURL() != null) browse(e.getURL().toString(), pane);
        });
        JDialog d = new JDialog(owner, "beswinged – Hilfe", false);
        d.add(new JScrollPane(pane), BorderLayout.CENTER);
        d.getRootPane().registerKeyboardAction(e -> d.setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setSize(new Dimension(820, 700));
        d.setLocationRelativeTo(owner);
        helpDialog = d;
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ Updates

    /** Ergebnis der Abfrage: neuestes Release oder null, wenn es keins gibt. */
    record Release(String tag, String name, String url, String published) { }

    static void checkForUpdates(JFrame owner) {
        owner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Release, Void>() {
            @Override protected Release doInBackground() throws Exception { return fetchLatest(); }
            @Override protected void done() {
                owner.setCursor(Cursor.getDefaultCursor());
                Release r;
                try {
                    r = get();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    int o = JOptionPane.showOptionDialog(owner,
                            "<html>Die Suche nach Updates ist fehlgeschlagen:<br>" + esc(String.valueOf(c.getMessage()))
                                    + "<br><br>Releases gibt es auch direkt auf GitHub.</html>",
                            "Suche nach Updates", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                            new Object[]{"Releases öffnen", "Schließen"}, "Schließen");
                    if (o == 0) browse(RELEASES_URL, owner);
                    return;
                }
                showUpdateResult(owner, r);
            }
        }.execute();
    }

    static Release fetchLatest() throws IOException {
        System.setProperty("java.net.useSystemProxies", "true");
        HttpURLConnection c = (HttpURLConnection) URI.create(LATEST_API).toURL().openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "beswinged/" + version());
        try {
            int code = c.getResponseCode();
            if (code == 404) return null; // noch kein Release
            if (code != 200) throw new IOException("GitHub antwortet mit HTTP " + code
                    + (code == 403 || code == 429 ? " (Abfragelimit erreicht, bitte später erneut versuchen)" : ""));
            try (InputStream in = c.getInputStream()) {
                Object o = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!(o instanceof Map<?, ?> m) || !(m.get("tag_name") instanceof String tag))
                    throw new IOException("Unerwartete Antwort von GitHub");
                Object name = m.get("name"), url = m.get("html_url"), pub = m.get("published_at");
                return new Release(tag, name instanceof String s ? s : tag,
                        url instanceof String s ? s : RELEASES_URL, pub instanceof String s ? s : "");
            }
        } finally {
            c.disconnect();
        }
    }

    private static void showUpdateResult(JFrame owner, Release r) {
        String mine = version();
        if (r == null) {
            JOptionPane.showMessageDialog(owner, "Auf GitHub gibt es noch kein veröffentlichtes Release.",
                    "Suche nach Updates", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String latest = r.tag().startsWith("v") ? r.tag().substring(1) : r.tag();
        String date = r.published().length() >= 10 ? " vom " + r.published().substring(0, 10) : "";
        int cmp = mine.equals("dev") ? -1 : compareVersions(latest, mine);
        boolean snapshot = mine.equals("dev") || mine.contains("SNAPSHOT");
        String text;
        boolean offer;
        if (cmp > 0) {
            text = "<b>Version " + esc(latest) + " ist verfügbar</b>" + esc(date) + ".<br>Installiert ist " + esc(mine) + ".";
            offer = true;
        } else if (snapshot) {
            text = "Sie verwenden die Entwicklerversion " + esc(mine) + ".<br>Neuestes Release: " + esc(latest) + esc(date) + ".";
            offer = true;
        } else if (cmp == 0) {
            text = "beswinged " + esc(mine) + " ist aktuell.";
            offer = false;
        } else {
            text = "Installiert ist " + esc(mine) + ", neuer als das neueste Release " + esc(latest) + ".";
            offer = false;
        }
        if (offer) {
            int o = JOptionPane.showOptionDialog(owner, "<html>" + text + "</html>", "Suche nach Updates",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                    new Object[]{"Download-Seite öffnen", "Schließen"}, "Download-Seite öffnen");
            if (o == 0) browse(r.url(), owner);
        } else {
            JOptionPane.showMessageDialog(owner, "<html>" + text + "</html>", "Suche nach Updates", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** Vergleicht numerische Versionen ("1.10.0" > "1.9.2"); Zusätze wie "-SNAPSHOT" zählen nicht. */
    static int compareVersions(String a, String b) {
        int[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int[] numbers(String v) {
        String core = v.startsWith("v") ? v.substring(1) : v;
        int cut = 0;
        while (cut < core.length() && (Character.isDigit(core.charAt(cut)) || core.charAt(cut) == '.')) cut++;
        String[] parts = core.substring(0, cut).split("\\.");
        List<Integer> out = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) out.add(Integer.parseInt(p.length() > 9 ? p.substring(0, 9) : p));
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    // ------------------------------------------------------------------ Über

    static void showAbout(JFrame owner) {
        JLabel text = new JLabel("<html><div style='width:330px'>"
                + "<span style='font-size:16pt'><b>beswinged</b></span><br>"
                + "Version " + esc(version()) + "<br><br>"
                + "Viewer für große JSON-Lines-Logdateien.<br>"
                + "Java " + esc(System.getProperty("java.version")) + " (" + esc(System.getProperty("java.vendor")) + ")<br><br>"
                + "Dieses Programm ist freie Software: Sie können es unter den Bedingungen der "
                + "<b>GNU General Public License, Version 3</b>, weitergeben und/oder verändern.<br><br>"
                + "Es wird OHNE JEDE GEWÄHRLEISTUNG bereitgestellt, auch ohne die implizite Gewährleistung "
                + "der MARKTREIFE oder der EIGNUNG FÜR EINEN BESTIMMTEN ZWECK. Details in der Lizenz.<br><br>"
                + PROJECT_URL + "</div></html>");
        List<Image> icons = icons();
        Image big = icons.isEmpty() ? null : icons.get(icons.size() - 3); // 64 px
        while (true) {
            int o = JOptionPane.showOptionDialog(owner, text, "Über beswinged", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, big == null ? null : new ImageIcon(big),
                    new Object[]{"Lizenz anzeigen", "Projektseite", "Schließen"}, "Schließen");
            if (o == 0) showLicense(owner);
            else if (o == 1) browse(PROJECT_URL, owner);
            else return;
        }
    }

    static void showLicense(JFrame owner) {
        String lic;
        try (InputStream in = HelpMenu.class.getResourceAsStream("/META-INF/LICENSE")) {
            lic = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            lic = null;
        }
        if (lic == null) {
            browse("https://www.gnu.org/licenses/gpl-3.0.html", owner);
            return;
        }
        JTextArea area = new JTextArea(lic);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(640, 520));
        JOptionPane.showMessageDialog(owner, sp, "Lizenz: GNU GPL v3", JOptionPane.PLAIN_MESSAGE);
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    static void browse(String url, java.awt.Component parent) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // unten anzeigen
        }
        JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(parent) != null ? SwingUtilities.getWindowAncestor(parent) : parent,
                "Bitte im Browser öffnen:\n" + url, "Link", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
