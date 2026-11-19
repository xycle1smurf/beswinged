package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * Auswahlfilter einer Spalte: Button in der Filterleiste, der ein Popup mit allen vorkommenden
 * (DISTINCT) Werten öffnet. Tippen filtert die Liste, Klick/Leertaste/Enter wählt Werte an oder ab.
 * Keine Auswahl = keine Einschränkung. Beim Schließen des Popups wird bei Änderungen
 * {@code onChange} aufgerufen.
 */
public final class ChoiceFilterButton extends JButton {

    /** Anzeige für den leeren Wert. */
    public static final String EMPTY_LABEL = "(leer)";

    private final String columnName;
    private final Runnable onChange;
    private List<String> values = List.of();
    private boolean loading = true;
    private boolean overflow;
    private final Set<String> selected = new LinkedHashSet<>();

    public ChoiceFilterButton(String columnName, Runnable onChange) {
        this.columnName = columnName;
        this.onChange = onChange;
        setFocusable(false);
        addActionListener(e -> showPopup());
        updateText();
    }

    public void setLoading() {
        loading = true;
        updateText();
    }

    public void setValues(List<String> values, boolean overflow) {
        this.values = values;
        this.overflow = overflow;
        this.loading = false;
        updateText();
    }


    /** Weitere Werte ergänzen (Tail). */
    public void addValues(java.util.Collection<String> more, java.util.Comparator<String> order, int limit) {
        if (loading || more.isEmpty()) return;
        Set<String> all = new LinkedHashSet<>(values);
        int before = all.size();
        for (String v : more) {
            if (all.size() >= limit) { if (!all.contains(v)) overflow = true; }
            else all.add(v);
        }
        if (all.size() == before) return;
        List<String> l = new java.util.ArrayList<>(all);
        l.sort(order);
        values = l;
        updateText();
    }

    public boolean isLoading() { return loading; }

    /** Ausgewählte Werte; leer = alle. */
    public Set<String> selection() { return Set.copyOf(selected); }

    /** Auswahl setzen (gespeicherter Filter); leer = alle. */
    public void setSelection(java.util.Collection<String> values) {
        selected.clear();
        selected.addAll(values);
        updateText();
    }

    public void clearSelection() {
        selected.clear();
        updateText();
    }

    private static String label(String v) { return v.isEmpty() ? EMPTY_LABEL : v; }

    private void updateText() {
        String sel;
        if (selected.isEmpty()) sel = "alle";
        else if (selected.size() == 1) sel = shorten(label(selected.iterator().next()));
        else sel = selected.size() + " ausgewählt";
        setText(columnName + ": " + sel + " ▾");
        if (selected.isEmpty()) setToolTipText(loading ? "Werte werden ermittelt…" : values.size() + " verschiedene Werte");
        else {
            StringBuilder sb = new StringBuilder("<html><b>Ausgewählt:</b>");
            int n = 0;
            for (String v : selected) {
                if (n++ == 30) { sb.append("<br>…"); break; }
                sb.append("<br>").append(escape(shorten(label(v))));
            }
            setToolTipText(sb.append("</html>").toString());
        }
    }

    private static String shorten(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ Popup

    private void showPopup() {
        Set<String> before = Set.copyOf(selected);
        JPopupMenu pm = new JPopupMenu();
        pm.setLayout(new BorderLayout());

        JTextField search = new JTextField();
        search.setToolTipText("Tippen zum Suchen — ↑/↓ wählen, Enter/Leertaste an- oder abwählen");
        DefaultListModel<String> listModel = new DefaultListModel<>();
        JList<String> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new CheckRenderer());
        list.setVisibleRowCount(16);
        JLabel info = new JLabel(" ");
        info.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JLabel chosen = new JLabel(" ");
        chosen.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        Runnable refreshChosen = () -> {
            if (selected.isEmpty()) chosen.setText("Ausgewählt: — (alle Werte)");
            else {
                List<String> l = new ArrayList<>();
                for (String v : selected) l.add(shorten(label(v)));
                String s = String.join(", ", l);
                chosen.setText("Ausgewählt: " + (s.length() > 80 ? s.substring(0, 80) + "…" : s));
                chosen.setToolTipText(String.join(", ", l));
            }
        };
        Runnable refilter = () -> {
            String q = search.getText().trim().toLowerCase(Locale.ROOT);
            listModel.clear();
            List<String> match = new ArrayList<>();
            for (String v : values) if (q.isEmpty() || label(v).toLowerCase(Locale.ROOT).contains(q)) match.add(v);
            listModel.addAll(match);
            if (!listModel.isEmpty()) list.setSelectedIndex(0);
            if (loading) info.setText("Werte werden noch ermittelt…");
            else info.setText(match.size() + " von " + values.size() + " Werten"
                    + (overflow ? " (Liste gekürzt: zu viele verschiedene Werte)" : ""));
        };
        Runnable toggleCurrent = () -> {
            String v = list.getSelectedValue();
            if (v == null) return;
            if (!selected.remove(v)) selected.add(v);
            list.repaint();
            refreshChosen.run();
            updateText();
        };

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refilter.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refilter.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refilter.run(); }
        });
        search.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int i = list.getSelectedIndex();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> { if (i + 1 < listModel.size()) { list.setSelectedIndex(i + 1); list.ensureIndexIsVisible(i + 1); } e.consume(); }
                    case KeyEvent.VK_UP -> { if (i > 0) { list.setSelectedIndex(i - 1); list.ensureIndexIsVisible(i - 1); } e.consume(); }
                    case KeyEvent.VK_ENTER -> { toggleCurrent.run(); e.consume(); }
                    case KeyEvent.VK_SPACE -> { if (search.getText().isEmpty()) { toggleCurrent.run(); e.consume(); } }
                    case KeyEvent.VK_ESCAPE -> { pm.setVisible(false); e.consume(); }
                    default -> { }
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    list.setSelectedIndex(i);
                    toggleCurrent.run();
                }
                search.requestFocusInWindow();
            }
        });

        JButton all = new JButton("Alle anzeigen");
        all.setToolTipText("Auswahl leeren: keine Einschränkung");
        all.addActionListener(e -> { selected.clear(); list.repaint(); refreshChosen.run(); updateText(); });
        JButton pickVisible = new JButton("Gefundene auswählen");
        pickVisible.addActionListener(e -> {
            for (int i = 0; i < listModel.size(); i++) selected.add(listModel.get(i));
            list.repaint(); refreshChosen.run(); updateText();
        });
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> pm.setVisible(false));

        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
        top.add(search, BorderLayout.NORTH);
        top.add(chosen, BorderLayout.SOUTH);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(info, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        buttons.add(all);
        buttons.add(pickVisible);
        buttons.add(ok);
        bottom.add(buttons, BorderLayout.SOUTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(Math.max(360, getWidth()), 300));
        pm.add(top, BorderLayout.NORTH);
        pm.add(sp, BorderLayout.CENTER);
        pm.add(bottom, BorderLayout.SOUTH);

        pm.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                if (!selected.equals(before)) SwingUtilities.invokeLater(onChange);
            }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });

        refilter.run();
        refreshChosen.run();
        pm.show(this, 0, getHeight());
        SwingUtilities.invokeLater(search::requestFocusInWindow);
    }

    private final class CheckRenderer extends JCheckBox implements ListCellRenderer<String> {
        CheckRenderer() { setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4)); }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            setText(label(value));
            setSelected(selected.contains(value));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }
}
