package jsonlogviewer;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Gibt JSON-Werte (aus {@link Json#parse}) als YAML aus, im Stil von {@code kubectl get -o yaml}. */
final class Yaml {

    private static final Pattern RESERVED = Pattern.compile("(?i)(true|false|yes|no|on|off|y|n|null|~)");
    private static final Pattern NUMBERISH = Pattern.compile("[-+]?(\\.?[0-9][0-9_:.eE+-]*|\\.inf|\\.nan|0x[0-9a-fA-F]+|0o[0-7]+)");
    private static final Pattern DATEISH = Pattern.compile("\\d{4}-\\d\\d?-\\d\\d?.*");

    private Yaml() { }

    static String dump(Object v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof Map<?, ?> m && !m.isEmpty()) writeMap(sb, m, 0, false);
        else if (v instanceof List<?> l && !l.isEmpty()) writeList(sb, l, 0);
        else sb.append(scalar(v, 0)).append('\n');
        return sb.toString();
    }

    /** @param inline erster Schlüssel steht schon hinter „- " */
    private static void writeMap(StringBuilder sb, Map<?, ?> m, int indent, boolean inline) {
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(first && inline)) sb.append(" ".repeat(indent));
            first = false;
            sb.append(key(String.valueOf(e.getKey()))).append(':');
            Object v = e.getValue();
            if (v instanceof Map<?, ?> cm) {
                if (cm.isEmpty()) sb.append(" {}\n");
                else { sb.append('\n'); writeMap(sb, cm, indent + 2, false); }
            } else if (v instanceof List<?> cl) {
                if (cl.isEmpty()) sb.append(" []\n");
                else { sb.append('\n'); writeList(sb, cl, indent); }
            } else {
                sb.append(' ').append(scalar(v, indent + 2)).append('\n');
            }
        }
    }

    private static void writeList(StringBuilder sb, List<?> l, int indent) {
        for (Object v : l) {
            sb.append(" ".repeat(indent)).append('-');
            if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) sb.append(" {}\n");
                else { sb.append(' '); writeMap(sb, m, indent + 2, true); }
            } else if (v instanceof List<?> cl) {
                if (cl.isEmpty()) sb.append(" []\n");
                else { sb.append('\n'); writeList(sb, cl, indent + 2); }
            } else {
                sb.append(' ').append(scalar(v, indent + 2)).append('\n');
            }
        }
    }

    private static String key(String k) {
        return plainOk(k) ? k : quote(k);
    }

    /** @param indent Einrückung für die Zeilen eines Blocktexts */
    private static String scalar(Object v, int indent) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Json.JsonNumber) return v.toString();
        String s = v.toString();
        if (s.indexOf('\n') >= 0 && blockOk(s)) {
            StringBuilder sb = new StringBuilder(s.endsWith("\n") ? "|" : "|-");
            String body = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
            String pad = " ".repeat(indent);
            for (String line : body.split("\n", -1)) {
                sb.append('\n');
                if (!line.isEmpty()) sb.append(pad).append(line);
            }
            return sb.toString();
        }
        return plainOk(s) ? s : quote(s);
    }

    private static boolean blockOk(String s) {
        if (s.startsWith(" ") || s.startsWith("\t") || s.endsWith("\n\n")) return false;
        for (String line : s.split("\n", -1)) if (line.endsWith(" ") || line.endsWith("\t")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\t') return false;
        }
        return true;
    }

    private static boolean plainOk(String s) {
        if (s.isEmpty() || s.isBlank()) return false;
        if (Character.isWhitespace(s.charAt(0)) || Character.isWhitespace(s.charAt(s.length() - 1))) return false;
        if ("-?:,[]{}#&*!|>'\"%@`".indexOf(s.charAt(0)) >= 0) return false;
        if (RESERVED.matcher(s).matches() || NUMBERISH.matcher(s).matches() || DATEISH.matcher(s).matches()) return false;
        if (s.contains(": ") || s.contains(" #") || s.endsWith(":")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '﻿') return false;
        }
        return true;
    }

    private static String quote(String s) {
        boolean ctrl = false;
        for (int i = 0; i < s.length() && !ctrl; i++) ctrl = s.charAt(i) < 0x20 || s.charAt(i) == 0x7F;
        if (!ctrl) return "'" + s.replace("'", "''") + "'";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) sb.append(String.format("\\x%02X", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
