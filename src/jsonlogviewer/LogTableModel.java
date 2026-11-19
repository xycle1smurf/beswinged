package jsonlogviewer;

import java.awt.Color;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.table.AbstractTableModel;

/**
 * Lazy TableModel: Zeilen werden erst gelesen und geparst, wenn die JTable sie anzeigt.
 * Ein LRU-Cache hält die zuletzt sichtbaren Zeilen.
 */
public final class LogTableModel extends AbstractTableModel {

    public record Row(String[] cells, Color background, boolean invalid) {}

    private static final int CACHE_SIZE = 20_000;
    private static final int MAX_CELL_CHARS = 2_000;

    private final LineIndex index;
    private final Schema schema;
    private int[] filter;      // null = alle Zeilen
    private int filterCount;
    private int rowCount;

    private final Map<Integer, Row> cache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Row> e) { return size() > CACHE_SIZE; }
    };

    public LogTableModel(LineIndex index, Schema schema) {
        this.index = index;
        this.schema = schema;
        this.rowCount = index == null ? 0 : index.lineCount();
    }

    public Schema schema() { return schema; }
    public LineIndex index() { return index; }
    public boolean isFiltered() { return filter != null; }

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

    /** Datei-Zeile -> View-Zeile, oder -1 falls herausgefiltert. */
    public int viewRowOf(int line) {
        if (filter == null) return line < rowCount ? line : -1;
        int idx = java.util.Arrays.binarySearch(filter, 0, filterCount, line);
        return idx >= 0 ? idx : -1;
    }

    /** Nächste View-Zeile mit Zeilennummer >= line (für "Gehe zu" im Filter). */
    public int nearestViewRow(int line) {
        if (filter == null) return Math.min(line, rowCount - 1);
        int idx = java.util.Arrays.binarySearch(filter, 0, filterCount, line);
        if (idx < 0) idx = -idx - 1;
        return Math.min(idx, filterCount - 1);
    }

    @Override public int getRowCount() { return rowCount; }
    @Override public int getColumnCount() { return schema.columns.size() + 1; }

    @Override public String getColumnName(int c) {
        return c == 0 ? "#" : schema.columns.get(c - 1).name();
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
            String[] cells = new String[schema.columns.size()];
            java.util.Arrays.fill(cells, "");
            cells[0] = "Lesefehler: " + e.getMessage();
            return new Row(cells, null, true);
        }
        return extract(schema, text);
    }

    static Row extract(Schema schema, String text) {
        int n = schema.columns.size();
        String[] cells = new String[n];
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException e) {
            java.util.Arrays.fill(cells, "");
            for (int k = 0; k < n; k++) if (Schema.RAW.equals(schema.columns.get(k).path())) cells[k] = cell(text);
            if (n > 0 && cells[0].isEmpty()) cells[0] = cell("⚠ kein JSON: " + text);
            return new Row(cells, null, true);
        }
        for (int k = 0; k < n; k++) {
            Schema.Column col = schema.columns.get(k);
            cells[k] = Schema.RAW.equals(col.path()) ? cell(text) : cell(Json.toDisplay(Json.get(root, col.segments())));
        }
        return new Row(cells, schema.colorFor(root), false);
    }

    /** Zelltext einzeilig und begrenzt halten (Rendering großer Werte ist teuer). */
    private static String cell(String s) {
        if (s.length() > MAX_CELL_CHARS) s = s.substring(0, MAX_CELL_CHARS) + " …";
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\t') >= 0)
            s = s.replace("\r\n", " ⏎ ").replace('\n', '⏎').replace('\r', ' ').replace('\t', ' ');
        return s;
    }
}
