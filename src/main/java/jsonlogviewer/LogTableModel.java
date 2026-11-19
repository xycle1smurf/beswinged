package jsonlogviewer;

import java.awt.Color;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.table.AbstractTableModel;

/**
 * Lazy TableModel: Zeilen werden erst gelesen und geparst, wenn die JTable sie anzeigt.
 * Ein LRU-Cache hält die zuletzt sichtbaren Zeilen.
 *
 * Model-Spalte 0 ist die Zeilennummer, Model-Spalte k+1 ist Schema-Spalte k.
 */
public final class LogTableModel extends AbstractTableModel {

    public record Row(String[] cells, Color background, boolean invalid) {}

    private static final int CACHE_SIZE = 20_000;
    private static final int MAX_CELL_CHARS = 2_000;

    private final LineIndex index;
    private final Schema schema;
    private final Schema.Column[] cols; // aktueller Stand (Sichtbarkeit, Formatter, Breite)
    private int[] filter;      // null = alle Zeilen
    private int filterCount;
    private int rowCount;

    private final Map<Integer, Row> cache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Row> e) { return size() > CACHE_SIZE; }
    };

    public LogTableModel(LineIndex index, Schema schema) {
        this.index = index;
        this.schema = schema;
        this.cols = schema.columns.toArray(new Schema.Column[0]);
        this.rowCount = index == null ? 0 : index.lineCount();
    }

    public Schema schema() { return schema; }
    public LineIndex index() { return index; }
    public boolean isFiltered() { return filter != null; }

    /** Anzahl Schema-Spalten (ohne Zeilennummer). */
    public int schemaColumnCount() { return cols.length; }

    /** Schema-Spalte k (0-basiert, ohne Zeilennummer). */
    public Schema.Column column(int k) { return cols[k]; }

    /** Ersetzt Schema-Spalte k; bei geändertem Formatter werden die Zellen neu berechnet. */
    public void setColumn(int k, Schema.Column c) {
        boolean reformat = c.formatter() != cols[k].formatter();
        cols[k] = c;
        if (reformat) {
            cache.clear();
            if (rowCount > 0) fireTableRowsUpdated(0, rowCount - 1); // erhält die Auswahl
        }
    }

    /** Neue indizierte Zeilen übernehmen (vom EDT aus periodisch aufgerufen). */
    public void refreshRowCount() {
        if (index == null || filter != null) return;
        int n = index.lineCount();
        if (n > rowCount) {
            int old = rowCount;
            rowCount = n;
            fireTableRowsInserted(old, n - 1);
        }
    }

    public void setFilter(int[] lines, int count) {
        this.filter = lines;
        this.filterCount = count;
        this.rowCount = lines == null ? (index == null ? 0 : index.lineCount()) : count;
        fireTableDataChanged();
    }

    /** View-Zeile -> Zeilennummer in der Datei (0-basiert). */
    public int lineAt(int viewRow) {
        return filter == null ? viewRow : filter[viewRow];
    }

    /** Nächste View-Zeile mit Zeilennummer >= line (für "Gehe zu" im Filter). */
    public int nearestViewRow(int line) {
        if (filter == null) return Math.min(line, rowCount - 1);
        int idx = Arrays.binarySearch(filter, 0, filterCount, line);
        if (idx < 0) idx = -idx - 1;
        return Math.min(idx, filterCount - 1);
    }

    @Override public int getRowCount() { return rowCount; }
    @Override public int getColumnCount() { return cols.length + 1; }

    @Override public String getColumnName(int c) {
        return c == 0 ? "#" : cols[c - 1].name();
    }

    @Override public Class<?> getColumnClass(int c) {
        return c == 0 ? Integer.class : String.class;
    }

    @Override public Object getValueAt(int r, int c) {
        if (c == 0) return lineAt(r) + 1;
        return row(r).cells()[c - 1];
    }

    public Row row(int viewRow) {
        int line = lineAt(viewRow);
        Row row = cache.get(line);
        if (row == null) {
            row = load(line);
            cache.put(line, row);
        }
        return row;
    }

    private Row load(int line) {
        String text;
        try {
            text = index.readLine(line);
        } catch (IOException e) {
            String[] cells = new String[cols.length];
            Arrays.fill(cells, "");
            cells[0] = "Lesefehler: " + e.getMessage();
            return new Row(cells, null, true);
        }
        return extract(text);
    }

    private Row extract(String text) {
        int n = cols.length;
        String[] cells = new String[n];
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException e) {
            Arrays.fill(cells, "");
            for (int k = 0; k < n; k++) if (cols[k].isRaw()) cells[k] = cell(text);
            if (n > 0 && cells[0].isEmpty()) cells[0] = cell("⚠ kein JSON: " + text);
            return new Row(cells, null, true);
        }
        for (int k = 0; k < n; k++) cells[k] = cell(displayValue(cols[k], root, text));
        return new Row(cells, schema.colorFor(root), false);
    }

    /** Wert einer Spalte, wie er angezeigt (und gefiltert) wird: inkl. Formatter. */
    static String displayValue(Schema.Column col, Object root, String rawLine) {
        if (col.isRaw()) return rawLine;
        return col.formatter().apply(Json.toDisplay(Json.get(root, col.segments())));
    }

    /** Zelltext einzeilig und begrenzt halten (Rendering großer Werte ist teuer). */
    private static String cell(String s) {
        if (s.length() > MAX_CELL_CHARS) s = s.substring(0, MAX_CELL_CHARS) + " …";
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\t') >= 0)
            s = s.replace("\r\n", " ⏎ ").replace('\n', '⏎').replace('\r', ' ').replace('\t', ' ');
        return s;
    }
}
