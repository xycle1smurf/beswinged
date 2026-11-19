package jsonlogviewer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimaler, schneller JSON-Parser (RFC 8259) ohne Fremdbibliotheken.
 * Ergebnis-Typen: Map (LinkedHashMap), List, String, JsonNumber, Boolean, null.
 */
public final class Json {

    /** Zahl im Originaltext, damit nichts an Genauigkeit verloren geht. */
    public record JsonNumber(String text) {
        @Override public String toString() { return text; }
    }

    public static final class ParseException extends RuntimeException {
        ParseException(String msg) { super(msg); }
    }

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("Unerwartete Zeichen nach dem JSON-Wert");
        return v;
    }

    // ------------------------------------------------------------------ Parser

    private Object value() {
        if (i >= s.length()) throw err("Unerwartetes Ende");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("Unerwartetes Zeichen '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        i++; // {
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw err("Objekt-Schlüssel erwartet");
            String key = string();
            ws();
            if (peek() != ':') throw err("':' erwartet");
            i++;
            ws();
            m.put(key, value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw err("',' oder '}' erwartet");
        }
    }

    private List<Object> array() {
        i++; // [
        List<Object> l = new ArrayList<>();
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return l; }
            throw err("',' oder ']' erwartet");
        }
    }

    private String string() {
        i++; // "
        int start = i;
        // Fast path: keine Escapes
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') { return s.substring(start, i++); }
            if (c == '\\') break;
            i++;
        }
        StringBuilder sb = new StringBuilder(s.length() - start + 16);
        sb.append(s, start, i);
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("Ungültiges \\u-Escape");
                    try {
                        sb.append((char) Integer.parseInt(s, i, i + 4, 16));
                    } catch (NumberFormatException ex) {
                        throw err("Ungültiges \\u-Escape");
                    }
                    i += 4;
                    break;
                default: throw err("Ungültiges Escape '\\" + e + "'");
            }
        }
        throw err("Nicht abgeschlossener String");
    }

    private JsonNumber number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
            else break;
        }
        return new JsonNumber(s.substring(start, i));
    }

    private void literal(String lit) {
        if (!s.startsWith(lit, i)) throw err("'" + lit + "' erwartet");
        i += lit.length();
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
        }
    }

    private ParseException err(String msg) {
        return new ParseException(msg + " (Position " + i + ")");
    }

    // ------------------------------------------------------------ Pfad-Zugriff

    /** Zerlegt einen Pfad wie {@code a.b[0].c} in Segmente. */
    public static String[] compilePath(String path) {
        String p = path.replace("[", ".").replace("]", "");
        List<String> segs = new ArrayList<>();
        for (String seg : p.split("\\.")) if (!seg.isEmpty()) segs.add(seg);
        return segs.toArray(new String[0]);
    }

    /**
     * Liefert den Wert am Pfad. Bei Objekten wird zuerst der längste zusammengesetzte
     * Schlüssel probiert, damit auch Schlüssel mit Punkt (z.B. "log.level") funktionieren.
     */
    public static Object get(Object node, String[] segs) {
        return get(node, segs, 0);
    }

    private static Object get(Object node, String[] segs, int k) {
        if (k == segs.length) return node;
        if (node instanceof Map<?, ?> m) {
            for (int j = segs.length; j > k; j--) {
                String key = j == k + 1 ? segs[k] : String.join(".", java.util.Arrays.copyOfRange(segs, k, j));
                if (m.containsKey(key)) {
                    Object r = get(m.get(key), segs, j);
                    if (r != null || j == segs.length) return r;
                }
            }
            return null;
        }
        if (node instanceof List<?> l) {
            try {
                int idx = Integer.parseInt(segs[k]);
                return idx >= 0 && idx < l.size() ? get(l.get(idx), segs, k + 1) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- Ausgabe

    /** Text für eine Tabellenzelle. */
    public static String toDisplay(Object v) {
        if (v == null) return "";
        if (v instanceof String str) return str;
        if (v instanceof Map || v instanceof List) return stringify(v, false);
        return v.toString();
    }

    public static String stringify(Object v, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, pretty, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int depth) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String str) { quote(sb, str); return; }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) newline(sb, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), pretty, depth + 1);
            }
            if (pretty) newline(sb, depth);
            sb.append('}');
            return;
        }
        if (v instanceof List<?> l) {
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) sb.append(',');
                if (pretty) newline(sb, depth + 1);
                write(sb, l.get(k), pretty, depth + 1);
            }
            if (pretty) newline(sb, depth);
            sb.append(']');
            return;
        }
        sb.append(v); // Boolean, JsonNumber
    }

    private static void newline(StringBuilder sb, int depth) {
        sb.append('\n');
        sb.append("  ".repeat(depth));
    }

    private static void quote(StringBuilder sb, String str) {
        sb.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
