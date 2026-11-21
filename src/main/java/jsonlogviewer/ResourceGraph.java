package jsonlogviewer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import jsonlogviewer.KubeResources.Health;
import jsonlogviewer.KubeResources.Res;

/**
 * Baumansicht der Objekte eines Namespaces wie in Argo CD: links der Namespace, rechts davon die Objekte, Kinder
 * (ReplicaSet, Pod, …) jeweils eine Spalte weiter, verbunden durch Linien. Strg+Mausrad zoomt, Ziehen verschiebt.
 */
final class ResourceGraph extends JComponent {

    static final int W = 262, H = 58, COL_GAP = 84, ROW_GAP = 14, PAD = 24;
    private static final Color BG = new Color(0xEEF2F5);
    private static final Color CARD = Color.WHITE;
    private static final Color BORDER = new Color(0xD5DEE5);
    private static final Color SELECT = new Color(0x0DADEA);
    private static final Color EDGE = new Color(0xA9B6C1);
    private static final Color TEXT = new Color(0x363C4A);
    private static final Color GREY = new Color(0x6D7F8B);
    private static final Color K8S = new Color(0x326CE5);
    private static final Color NS = new Color(0xFF7E5F);
    private static final Font NAME_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font INFO_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private static final Font ICON_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 9);

    private Res root;
    private final Map<Res, Rectangle> boxes = new IdentityHashMap<>();
    private final List<Res> order = new ArrayList<>();      // Zeichenreihenfolge (sichtbare Kacheln)
    private final Set<Res> matched = new HashSet<>();       // erfüllt den Filter selbst (sonst nur als Vorfahr sichtbar)
    private Predicate<Res> filter = r -> true;
    private double zoom = 1.0;
    private Res selected;
    private String selectedKey;
    private Consumer<Res> onOpen = r -> { };
    private Consumer<MouseEvent> onPopup = e -> { };
    private Dimension size = new Dimension(400, 200);

    ResourceGraph() {
        setOpaque(true);
        setBackground(BG);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter ma = new MouseAdapter() {
            private Point dragFrom;

            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                Res r = at(e.getPoint());
                if (r != null || !e.isPopupTrigger()) select(r);
                if (e.isPopupTrigger()) { onPopup.accept(e); return; }
                dragFrom = r == null && SwingUtilities.isLeftMouseButton(e) ? e.getLocationOnScreen() : null;
                if (r != null && e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) onOpen.accept(r);
            }

            @Override public void mouseReleased(MouseEvent e) {
                dragFrom = null;
                setCursor(null);
                if (e.isPopupTrigger()) onPopup.accept(e);
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (dragFrom == null || !(getParent() instanceof JViewport vp)) return;
                setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
                Point now = e.getLocationOnScreen();
                Rectangle view = vp.getViewRect();
                view.translate(dragFrom.x - now.x, dragFrom.y - now.y);
                scrollRectToVisible(view);
                dragFrom = now;
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (!e.isControlDown()) { getParent().dispatchEvent(SwingUtilities.convertMouseEvent(ResourceGraph.this, e, getParent())); return; }
                setZoom(zoom * (e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1), e.getPoint());
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    // ------------------------------------------------------------------ Daten

    /** Neuer Stand; Auswahl bleibt (über Art/Name) erhalten. */
    void setRoot(Res root) {
        this.root = root;
        relayout();
    }

    void setFilter(Predicate<Res> filter) {
        this.filter = filter;
        relayout();
    }

    void setOnOpen(Consumer<Res> c) { onOpen = c; }

    void setOnPopup(Consumer<MouseEvent> c) { onPopup = c; }

    Res selected() { return selected; }

    double zoom() { return zoom; }

    int visibleCount() { return Math.max(0, order.size() - 1); }

    void select(Res r) {
        selected = r;
        selectedKey = r == null ? null : r.key();
        repaint();
    }

    Res at(Point p) {
        double x = p.x / zoom, y = p.y / zoom;
        for (Res r : order) if (boxes.get(r).contains(x, y)) return r;
        return null;
    }

    /** Ausschnitt so zoomen, dass alles in den sichtbaren Bereich passt (höchstens 100 %). */
    void fit() {
        if (!(getParent() instanceof JViewport vp) || order.isEmpty()) return;
        Dimension ext = vp.getExtentSize();
        double z = Math.min(1.0, Math.min(ext.getWidth() / (size.width + 2.0), ext.getHeight() / (size.height + 2.0)));
        setZoom(Math.max(0.25, z), null);
    }

    void setZoom(double z, Point anchor) {
        z = Math.max(0.25, Math.min(2.0, z));
        if (Math.abs(z - zoom) < 1e-6) return;
        Rectangle view = getParent() instanceof JViewport vp ? vp.getViewRect() : null;
        double old = zoom;
        zoom = z;
        revalidate();
        repaint();
        if (view != null) {
            Point a = anchor != null ? anchor : new Point(view.x + view.width / 2, view.y + view.height / 2);
            int dx = a.x - view.x, dy = a.y - view.y;
            int nx = (int) Math.round(a.x * z / old) - dx, ny = (int) Math.round(a.y * z / old) - dy;
            SwingUtilities.invokeLater(() -> ((JViewport) getParent()).setViewPosition(new Point(Math.max(0, nx), Math.max(0, ny))));
        }
        firePropertyChange("zoom", old, z);
    }

    /** Erste Ansicht: passt alles bei mindestens 70 % hinein, wird eingepasst, sonst 100 % mit dem Namespace im Blick. */
    void initialView() {
        if (!(getParent() instanceof JViewport vp) || root == null) return;
        Dimension ext = vp.getExtentSize();
        double z = Math.min(1.0, Math.min(ext.getWidth() / (size.width + 2.0), ext.getHeight() / (size.height + 2.0)));
        if (z >= 0.7) { fit(); return; }
        setZoom(1.0, null);
        Rectangle b = boxes.get(root);
        SwingUtilities.invokeLater(() -> vp.setViewPosition(new Point(0, Math.max(0, Math.min(b.y + H / 2 - ext.height / 2,
                getPreferredSize().height - ext.height)))));
    }

    /** Kachel des Objekts in den sichtbaren Bereich holen. */
    void reveal(Res r) {
        Rectangle b = boxes.get(r);
        if (b == null) return;
        scrollRectToVisible(new Rectangle((int) ((b.x - PAD) * zoom), (int) ((b.y - PAD) * zoom),
                (int) ((b.width + 2 * PAD) * zoom), (int) ((b.height + 2 * PAD) * zoom)));
    }

    Res find(String key) {
        for (Res r : order) if (r.key().equals(key)) return r;
        return null;
    }

    // ------------------------------------------------------------------ Layout

    private void relayout() {
        boxes.clear();
        order.clear();
        matched.clear();
        if (root == null) { size = new Dimension(400, 200); revalidate(); repaint(); return; }
        visible(root);
        double[] row = {0};
        int[] maxDepth = {0};
        place(root, 0, row, maxDepth);
        size = new Dimension(PAD * 2 + (maxDepth[0] + 1) * W + maxDepth[0] * COL_GAP,
                PAD * 2 + (int) Math.ceil(row[0]) * (H + ROW_GAP) - ROW_GAP);
        selected = selectedKey == null ? null : find(selectedKey);
        revalidate();
        repaint();
    }

    /** Merkt sichtbare Objekte: der Filter trifft sie selbst oder ein Nachfahre. */
    private boolean visible(Res r) {
        boolean self = r.isRoot() || filter.test(r);
        if (self && !r.isRoot()) matched.add(r);
        boolean any = false;
        for (Res c : r.children) any |= visible(c);
        if (self || any) { boxes.put(r, new Rectangle()); return true; }
        return false;
    }

    /** Zeilen werden von oben vergeben; Eltern stehen mittig neben ihren Kindern. */
    private double place(Res r, int depth, double[] row, int[] maxDepth) {
        maxDepth[0] = Math.max(maxDepth[0], depth);
        List<Double> ys = new ArrayList<>();
        order.add(r);
        for (Res c : r.children) if (boxes.containsKey(c)) ys.add(place(c, depth + 1, row, maxDepth));
        double y;
        if (ys.isEmpty()) y = row[0]++;
        else y = (ys.get(0) + ys.get(ys.size() - 1)) / 2;
        boxes.get(r).setBounds(PAD + depth * (W + COL_GAP), PAD + (int) Math.round(y * (H + ROW_GAP)), W, H);
        return y;
    }

    @Override public Dimension getPreferredSize() {
        return new Dimension((int) Math.ceil(size.width * zoom), (int) Math.ceil(size.height * zoom));
    }

    // ------------------------------------------------------------------ Zeichnen

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        if (root == null) { g.dispose(); return; }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(zoom, zoom);
        Rectangle clip = g.getClipBounds();
        g.setStroke(new BasicStroke(1.3f));
        for (Res r : order) {
            Rectangle p = boxes.get(r);
            for (Res c : r.children) {
                Rectangle b = boxes.get(c);
                if (b == null) continue;
                if (clip != null && !clip.intersects(new Rectangle(p.x + p.width, Math.min(p.y, b.y), b.x - p.x - p.width, Math.abs(b.y - p.y) + H))) continue;
                double x1 = p.getMaxX(), y1 = p.getCenterY(), x2 = b.x, y2 = b.getCenterY(), mx = (x1 + x2) / 2;
                g.setColor(EDGE);
                g.draw(new CubicCurve2D.Double(x1, y1, mx, y1, mx, y2, x2 - 4, y2));
                Path2D arrow = new Path2D.Double();
                arrow.moveTo(x2, y2);
                arrow.lineTo(x2 - 6, y2 - 3.5);
                arrow.lineTo(x2 - 6, y2 + 3.5);
                arrow.closePath();
                g.fill(arrow);
            }
        }
        for (Res r : order) {
            Rectangle b = boxes.get(r);
            if (clip != null && !clip.intersects(b.x - 4, b.y - 4, b.width + 8, b.height + 8)) continue;
            paintNode(g, r, b, !r.isRoot() && !matched.contains(r));
        }
        g.dispose();
    }

    private void paintNode(Graphics2D g0, Res r, Rectangle b, boolean dim) {
        Graphics2D g = (Graphics2D) g0.create();
        if (dim) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        RoundRectangle2D card = new RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 8, 8);
        g.setColor(new Color(0, 0, 0, 22));
        g.fill(new RoundRectangle2D.Double(b.x, b.y + 2, b.width, b.height, 8, 8));
        g.setColor(CARD);
        g.fill(card);
        boolean sel = r == selected;
        g.setColor(sel ? SELECT : BORDER);
        g.setStroke(new BasicStroke(sel ? 2.2f : 1f));
        g.draw(card);
        if (r.health != null && r.health != Health.HEALTHY) { // farbiger Rand links für Problemfälle
            g.setColor(new Color(r.health.rgb));
            g.fill(new RoundRectangle2D.Double(b.x + 1, b.y + 1, 4, b.height - 2, 4, 4));
        }

        // Symbol
        int cx = b.x + 26, cy = b.y + b.height / 2;
        g.setColor(r.isRoot() ? NS : K8S);
        g.fill(heptagon(cx, cy, 17));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(heptagon(cx, cy, 14.5));
        String ab = r.abbr();
        Font f = ICON_FONT;
        while (g.getFontMetrics(f).stringWidth(ab) > 24 && f.getSize2D() > 6) f = f.deriveFont(f.getSize2D() - 0.5f);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(ab, cx - fm.stringWidth(ab) / 2f, cy + fm.getAscent() / 2f - 1);

        // Texte
        int tx = b.x + 52, right = b.x + b.width - 10;
        String age = KubeResources.age(r.created);
        g.setFont(INFO_FONT);
        FontMetrics fi = g.getFontMetrics();
        int ageW = age.isEmpty() ? 0 : fi.stringWidth(age) + 8;
        g.setFont(NAME_FONT);
        g.setColor(TEXT);
        FontMetrics fn = g.getFontMetrics();
        String[] name = splitName(r.name, fn, right - tx - ageW, right - tx);
        int nameY = name.length == 1 ? b.y + 23 : b.y + 17;
        g.drawString(name[0], tx, nameY);
        if (name.length > 1) g.drawString(name[1], tx, nameY + 14);
        g.setFont(INFO_FONT);
        if (!age.isEmpty()) {
            g.setColor(GREY);
            g.drawString(age, right - fi.stringWidth(age), nameY - 1);
        }
        int infoY = name.length == 1 ? b.y + 43 : b.y + 48;
        int ix = tx;
        if (r.health != null) {
            paintHealth(g, r.health, ix + 6, infoY - 4, 6);
            ix += 16;
        }
        g.setColor(GREY);
        String line = r.kind + (r.info.isEmpty() ? "" : "  ·  " + r.info);
        g.drawString(ellipsize(line, fi, right - ix), ix, infoY);
        g.dispose();
    }

    private static Path2D heptagon(double cx, double cy, double rad) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < 7; i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / 7;
            double x = cx + rad * Math.cos(a), y = cy + rad * Math.sin(a);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    /** Symbole wie in Argo CD: Herz (gesund), gebrochenes Herz (fehlerhaft), Kreis (läuft), Pause, Fragezeichen. */
    static void paintHealth(Graphics2D g0, Health h, double cx, double cy, double s) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color c = new Color(h.rgb);
        g.setColor(c);
        switch (h) {
            case HEALTHY, DEGRADED -> {
                Path2D heart = heart(cx, cy, s);
                g.fill(heart);
                if (h == Health.DEGRADED) {
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke((float) (s / 4)));
                    Path2D crack = new Path2D.Double();
                    crack.moveTo(cx, cy - s * 0.55);
                    crack.lineTo(cx - s * 0.25, cy - s * 0.05);
                    crack.lineTo(cx + s * 0.2, cy + s * 0.25);
                    crack.lineTo(cx, cy + s * 0.75);
                    g.draw(crack);
                }
            }
            case PROGRESSING -> {
                g.setStroke(new BasicStroke((float) (s / 3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(cx - s * 0.85, cy - s * 0.85, s * 1.7, s * 1.7, 60, 280, Arc2D.OPEN));
            }
            case SUSPENDED -> {
                g.fill(new Rectangle2DD(cx - s * 0.75, cy - s * 0.8, s * 0.5, s * 1.6).shape());
                g.fill(new Rectangle2DD(cx + s * 0.25, cy - s * 0.8, s * 0.5, s * 1.6).shape());
            }
            case MISSING, UNKNOWN -> {
                g.fill(new java.awt.geom.Ellipse2D.Double(cx - s, cy - s, 2 * s, 2 * s));
                g.setColor(Color.WHITE);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(s * 1.6)));
                String q = h == Health.MISSING ? "!" : "?";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(q, (float) (cx - fm.stringWidth(q) / 2.0), (float) (cy + fm.getAscent() / 2.0 - 1));
            }
        }
        g.dispose();
    }

    private record Rectangle2DD(double x, double y, double w, double h) {
        java.awt.Shape shape() { return new RoundRectangle2D.Double(x, y, w, h, w / 2, w / 2); }
    }

    private static Path2D heart(double cx, double cy, double s) {
        Path2D p = new Path2D.Double();
        p.moveTo(cx, cy + s * 0.9);
        p.curveTo(cx - s * 1.4, cy, cx - s * 1.1, cy - s * 1.2, cx, cy - s * 0.45);
        p.curveTo(cx + s * 1.1, cy - s * 1.2, cx + s * 1.4, cy, cx, cy + s * 0.9);
        p.closePath();
        return p;
    }

    static String ellipsize(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) return s;
        String dots = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(s.substring(0, mid) + dots) <= width) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + dots;
    }

    // ------------------------------------------------------------------ Tooltip

    @Override public String getToolTipText(MouseEvent e) {
        Res r = at(e.getPoint());
        if (r == null) return null;
        StringBuilder sb = new StringBuilder("<html><b>").append(esc(r.kind)).append(' ').append(esc(r.name)).append("</b>");
        if (r.health != null) sb.append("<br>Zustand: <font color='#").append(String.format("%06X", r.health.rgb)).append("'>")
                .append(r.health.label).append("</font>");
        if (!r.message.isEmpty()) sb.append("<br>").append(esc(wrap(r.message)));
        if (!r.info.isEmpty()) sb.append("<br>").append(esc(r.info));
        if (r.created != null) sb.append("<br>Erstellt: ").append(r.created).append(" (").append(KubeResources.age(r.created)).append(')');
        if (!r.isRoot()) sb.append("<br><i>Doppelklick: Manifest, Rechtsklick: Menü</i>");
        return sb.append("</html>").toString();
    }

    /**
     * Teilt einen zu langen Namen auf zwei Zeilen: bevorzugt nach dem letzten Zeichen außerhalb von [a-zA-Z0-9]
     * (z.B. '-', '.', '_'), das noch in die erste Zeile passt; sonst hart an der Breite. Die zweite Zeile wird
     * bei Bedarf mit "…" gekürzt.
     */
    static String[] splitName(String s, FontMetrics fm, int width1, int width2) {
        if (fm.stringWidth(s) <= width1) return new String[]{s};
        int fit = 0; // Anzahl Zeichen, die in die erste Zeile passen
        while (fit < s.length() && fm.stringWidth(s.substring(0, fit + 1)) <= width1) fit++;
        if (fit == 0) return new String[]{ellipsize(s, fm, width1)};
        int cut = fit;
        for (int i = fit - 1; i > 0; i--) {
            char ch = s.charAt(i);
            if (!(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9')) { cut = i + 1; break; }
        }
        if (cut < fit / 3) cut = fit; // Trennzeichen ganz vorn: lieber die erste Zeile füllen
        return new String[]{s.substring(0, cut), ellipsize(s.substring(cut), fm, width2)};
    }

    private static String wrap(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
