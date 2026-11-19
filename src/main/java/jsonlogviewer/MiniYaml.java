package jsonlogviewer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sehr kleiner YAML-Parser für Konfigurationsdateien.
 * Unterstützt: Block-Mappings, Block-Listen (auch "- key: value"), Skalare
 * (unquoted, 'single', "double"), einfache Flow-Listen [a, b], Kommentare (#).
 * Nicht unterstützt: Anker/Aliase, Multi-Line-Strings (| >), Flow-Mappings {..}, Tags.
 * Ergebnis-Typen: Map, List, String (Skalare bleiben Strings).
 */
public final class MiniYaml {

    public static final class YamlException extends RuntimeException {
        YamlException(String msg) { super(msg); }
    }

    private static final class Line {
        int indent;
        String text;
        final int number;
        Line(int indent, String text, int number) { this.indent = indent; this.text = text; this.number = number; }
    }

    private final List<Line> lines = new ArrayList<>();
    private int pos;

    private MiniYaml(String src) {
        String[] raw = src.split("\r?\n", -1);
        for (int n = 0; n < raw.length; n++) {
            String l = stripComment(raw[n]);
            if (l.isBlank() || l.trim().equals("---")) continue;
            int ind = 0;
            while (ind < l.length() && l.charAt(ind) == ' ') ind++;
            if (ind < l.length() && l.charAt(ind) == '\t')
                throw new YamlException("Zeile " + (n + 1) + ": Tabs sind zur Einrückung nicht erlaubt");
            lines.add(new Line(ind, l.substring(ind).stripTrailing(), n + 1));
        }
    }

    public static Object parse(String src) {
        MiniYaml y = new MiniYaml(src);
        if (y.lines.isEmpty()) return new LinkedHashMap<String, Object>();
        Object result = y.block(y.lines.get(0).indent);
        if (y.pos < y.lines.size()) throw y.err(y.lines.get(y.pos), "Unerwartete Einrückung");
        return result;
    }

    private Object block(int indent) {
        return isListItem(lines.get(pos)) ? list(indent) : map(indent);
    }

    private Map<String, Object> map(int indent) {
        Map<String, Object> m = new LinkedHashMap<>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent < indent) break;
            if (l.indent > indent) throw err(l, "Unerwartete Einrückung");
            if (isListItem(l)) break;
            int colon = keySeparator(l.text);
            if (colon < 0) throw err(l, "'schlüssel: wert' erwartet");
            String key = unquote(l.text.substring(0, colon).trim());
            String rest = l.text.substring(colon + 1).trim();
            pos++;
            Object value;
            if (rest.isEmpty()) {
                if (pos < lines.size()) {
                    Line next = lines.get(pos);
                    if (next.indent > indent || (next.indent == indent && isListItem(next))) {
                        value = block(next.indent);
                    } else value = null;
                } else value = null;
            } else value = scalar(rest);
            m.put(key, value);
        }
        return m;
    }

    private List<Object> list(int indent) {
        List<Object> list = new ArrayList<>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent < indent) break;
            if (l.indent > indent) throw err(l, "Unerwartete Einrückung");
            if (!isListItem(l)) break;
            String rest = l.text.substring(1).trim();
            if (rest.isEmpty()) {
                pos++;
                if (pos < lines.size() && lines.get(pos).indent > indent) list.add(block(lines.get(pos).indent));
                else list.add(null);
            } else if (keySeparator(rest) >= 0 || rest.startsWith("- ")) {
                // "- key: value" -> die Zeile wird zu einem Mapping-Eintrag mit größerer Einrückung
                int offset = l.text.indexOf(rest);
                l.indent = l.indent + offset;
                l.text = rest;
                list.add(block(l.indent));
            } else {
                list.add(scalar(rest));
                pos++;
            }
        }
        return list;
    }

    private static boolean isListItem(Line l) {
        return l.text.equals("-") || l.text.startsWith("- ");
    }

    /** Position des ':' das Schlüssel und Wert trennt, -1 falls keiner. */
    private static int keySeparator(String t) {
        char q = 0;
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (q != 0) { if (c == q) q = 0; continue; }
            if ((c == '"' || c == '\'') && k == 0) { q = c; continue; }
            if (c == ':' && (k + 1 == t.length() || t.charAt(k + 1) == ' ')) return k;
        }
        return -1;
    }

    private static Object scalar(String t) {
        if (t.startsWith("[") && t.endsWith("]")) {
            List<Object> l = new ArrayList<>();
            String inner = t.substring(1, t.length() - 1).trim();
            if (!inner.isEmpty()) for (String part : splitFlow(inner)) l.add(unquote(part.trim()));
            return l;
        }
        if (t.equals("~") || t.equals("null")) return null;
        return unquote(t);
    }

    private static List<String> splitFlow(String s) {
        List<String> parts = new ArrayList<>();
        char q = 0;
        int start = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') q = c;
            else if (c == ',') { parts.add(s.substring(start, k)); start = k + 1; }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static String unquote(String t) {
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length() - 1).replace("''", "'");
        }
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            String inner = t.substring(1, t.length() - 1);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < inner.length(); k++) {
                char c = inner.charAt(k);
                if (c == '\\' && k + 1 < inner.length()) {
                    char e = inner.charAt(++k);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append('\\').append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }
        return t;
    }

    /** Entfernt Kommentare ('#' am Anfang oder nach Leerzeichen, außerhalb von Quotes). */
    private static String stripComment(String l) {
        char q = 0;
        for (int k = 0; k < l.length(); k++) {
            char c = l.charAt(k);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') {
                if (k == 0 || " :-[,".indexOf(l.charAt(k - 1)) >= 0) q = c;
            } else if (c == '#' && (k == 0 || l.charAt(k - 1) == ' ' || l.charAt(k - 1) == '\t')) {
                return l.substring(0, k);
            }
        }
        return l;
    }

    private YamlException err(Line l, String msg) {
        return new YamlException("Zeile " + l.number + ": " + msg);
    }
}
