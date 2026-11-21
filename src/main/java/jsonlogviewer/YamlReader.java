package jsonlogviewer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Liest ein YAML-Dokument, wie es Kubernetes-Manifeste verwenden, in dieselben Typen wie {@link Json#parse}:
 * Map (LinkedHashMap), List, String, Boolean, {@link Json.JsonNumber}, null.
 * <p>Unterstützt Block-Mappings und -Listen (auch Listen auf Höhe des Schlüssels und "- key: value"),
 * Blocktexte ({@code | |- |+ > >-}), einfache/doppelte Anführungszeichen, Flow-Collections ({@code {…} […]}),
 * Kommentare und {@code ---}. Nicht unterstützt: Anker/Aliase, Tags, mehrere Dokumente.
 * Skalare werden wie in YAML 1.1 (kubectl) aufgelöst: {@code true/yes/on}, Zahlen, {@code null/~}.
 */
final class YamlReader {

    static final class YamlException extends RuntimeException {
        final int line;
        YamlException(int line, String msg) { super((line > 0 ? "Zeile " + line + ": " : "") + msg); this.line = line; }
    }

    private static final Pattern INT = Pattern.compile("[-+]?(0|[1-9][0-9_]*|0[0-7_]+|0x[0-9a-fA-F_]+|0o[0-7_]+|0b[01_]+)");
    private static final Pattern FLOAT = Pattern.compile("[-+]?([0-9][0-9_]*)?\\.[0-9_]*([eE][-+]?[0-9]+)?|[-+]?[0-9][0-9_]*[eE][-+]?[0-9]+");
    private static final Pattern BOOL_TRUE = Pattern.compile("y|Y|yes|Yes|YES|true|True|TRUE|on|On|ON");
    private static final Pattern BOOL_FALSE = Pattern.compile("n|N|no|No|NO|false|False|FALSE|off|Off|OFF");

    private final String[] lines;
    private int pos;

    private YamlReader(String src) {
        if (!src.isEmpty() && src.charAt(0) == '﻿') src = src.substring(1);
        lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    static Object parse(String src) {
        YamlReader r = new YamlReader(src);
        r.skipBlank();
        if (r.pos < r.lines.length && r.lines[r.pos].startsWith("---")) {
            String rest = r.lines[r.pos].substring(3).trim();
            if (!rest.isEmpty() && !rest.startsWith("#")) throw new YamlException(r.pos + 1, "Inhalt hinter --- wird nicht unterstützt");
            r.pos++;
            r.skipBlank();
        }
        if (r.pos >= r.lines.length) return null;
        int ind = indent(r.lines[r.pos], r.pos);
        if (ind != 0) throw new YamlException(r.pos + 1, "Das Dokument muss am Zeilenanfang beginnen");
        Object v = r.node(0);
        r.skipBlank();
        if (r.pos < r.lines.length) {
            String l = r.lines[r.pos];
            if (l.startsWith("---")) throw new YamlException(r.pos + 1, "Mehrere Dokumente werden nicht unterstützt");
            if (!l.startsWith("...")) throw new YamlException(r.pos + 1, "Unerwartete Einrückung oder Zeile");
        }
        return v;
    }

    // ------------------------------------------------------------------ Blockstruktur

    /** Knoten, dessen erste Zeile bei {@code pos} mit Einrückung {@code ind} beginnt. */
    private Object node(int ind) {
        String t = lines[pos].substring(ind);
        if (isSeqItem(t)) return seq(ind);
        if (keyEnd(t) >= 0) return map(ind);
        int start = pos++;
        return inline(strip(t), ind - 1, start);
    }

    private Map<String, Object> map(int ind) {
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            skipBlank();
            if (pos >= lines.length || isDocEnd(lines[pos])) break;
            int i = indent(lines[pos], pos);
            if (i < ind) break;
            if (i > ind) throw new YamlException(pos + 1, "Unerwartete Einrückung (erwartet " + ind + " Leerzeichen, gefunden " + i + ")");
            String t = lines[pos].substring(ind);
            if (isSeqItem(t)) break; // Liste auf Höhe eines Schlüssels gehört zum vorigen Schlüssel – hier Fehler im Aufrufer
            int k = keyEnd(t);
            if (k < 0) throw new YamlException(pos + 1, "„Schlüssel: Wert“ erwartet: " + t.trim());
            String key = key(t.substring(0, k).trim(), pos);
            if (m.containsKey(key)) throw new YamlException(pos + 1, "Schlüssel „" + key + "“ doppelt");
            int line = pos++;
            m.put(key, value(strip(t.substring(k + 1)).strip(), ind, true, line));
        }
        return m;
    }

    private List<Object> seq(int ind) {
        List<Object> l = new ArrayList<>();
        while (true) {
            skipBlank();
            if (pos >= lines.length || isDocEnd(lines[pos])) break;
            int i = indent(lines[pos], pos);
            if (i != ind) {
                if (i > ind) throw new YamlException(pos + 1, "Unerwartete Einrückung in der Liste");
                break;
            }
            String t = lines[pos].substring(ind);
            if (!isSeqItem(t)) break;
            int off = 1;
            while (off < t.length() && t.charAt(off) == ' ') off++;
            String rest = t.substring(off);
            if (rest.isEmpty() || rest.startsWith("#")) {
                int line = pos++;
                l.add(value("", ind, false, line));
            } else if (isSeqItem(rest) || (keyEnd(rest) >= 0 && !startsInline(rest))) {
                // "- key: v" bzw. "- - x": der Rest bildet einen eingerückten Block ab Spalte ind+off
                lines[pos] = " ".repeat(ind + off) + rest;
                l.add(node(ind + off));
            } else {
                int line = pos++;
                l.add(value(strip(rest), ind, false, line));
            }
        }
        return l;
    }

    /**
     * Wert hinter "key:" bzw. "- ".
     * @param rest   Text in derselben Zeile (ohne Kommentar), {@code pos} steht schon auf der Folgezeile
     * @param ind    Einrückung der Zeile mit Schlüssel bzw. Spiegelstrich
     * @param inMap  Wert eines Schlüssels (dann darf eine Liste auf gleicher Einrückung folgen)
     */
    private Object value(String rest, int ind, boolean inMap, int line) {
        if (rest.isEmpty()) {
            int save = pos;
            skipBlank();
            if (pos >= lines.length || isDocEnd(lines[pos])) { pos = save; return null; }
            int i = indent(lines[pos], pos);
            String t = lines[pos].substring(i);
            if (i > ind) return node(i);
            if (inMap && i == ind && isSeqItem(t)) return seq(ind);
            pos = save;
            return null;
        }
        return inline(rest, ind, line);
    }

    /** Skalar oder Flow-Collection, die in Zeile {@code line} beginnt; Fortsetzungszeilen müssen tiefer als {@code ind} stehen. */
    private Object inline(String rest, int ind, int line) {
        char c = rest.charAt(0);
        if (c == '&' || c == '*') throw new YamlException(line + 1, "Anker und Aliase (& *) werden nicht unterstützt");
        if (c == '!') throw new YamlException(line + 1, "Tags (!) werden nicht unterstützt");
        if (c == '|' || c == '>') return blockScalar(rest, ind, line);
        if (c == '[' || c == '{') {
            StringBuilder sb = new StringBuilder(rest);
            while (!balanced(sb) && pos < lines.length) {
                sb.append(' ').append(strip(lines[pos].trim()));
                pos++;
            }
            Flow f = new Flow(sb.toString(), line + 1);
            Object v = f.value();
            f.ws();
            if (f.i < f.s.length()) throw new YamlException(line + 1, "Unerwartetes Zeichen hinter " + c + "…: " + f.s.substring(f.i));
            return v;
        }
        if (c == '"' || c == '\'') {
            StringBuilder sb = new StringBuilder(rest);
            int end = quoteEnd(sb, 0);
            while (end < 0 && pos < lines.length) {
                sb.append('\n').append(lines[pos].trim());
                pos++;
                end = quoteEnd(sb, 0);
            }
            if (end < 0) throw new YamlException(line + 1, "Anführungszeichen nicht geschlossen");
            String tail = strip(sb.substring(end + 1)).trim();
            if (!tail.isEmpty()) throw new YamlException(line + 1, "Unerwarteter Text hinter dem Anführungszeichen: " + tail);
            return unquote(sb.substring(0, end + 1), line + 1);
        }
        // einfacher Skalar, ggf. über mehrere tiefer eingerückte Zeilen (zusammengefügt mit Leerzeichen)
        StringBuilder sb = new StringBuilder(rest.trim());
        while (pos < lines.length && !lines[pos].isBlank() && indent(lines[pos], pos) > ind && !isComment(lines[pos])) {
            String t = strip(lines[pos].trim());
            if (keyEnd(t) >= 0 || isSeqItem(t)) break;
            sb.append(' ').append(t);
            pos++;
        }
        return resolve(sb.toString());
    }

    private Object blockScalar(String header, int ind, int line) {
        boolean literal = header.charAt(0) == '|';
        char chomp = 'c';
        int explicit = 0;
        for (int i = 1; i < header.length(); i++) {
            char ch = header.charAt(i);
            if (ch == '-' || ch == '+') chomp = ch;
            else if (ch >= '1' && ch <= '9') explicit = ch - '0';
            else if (ch == ' ' || ch == '\t') { if (!strip(header.substring(i)).isBlank()) throw new YamlException(line + 1, "Unerwarteter Text hinter " + header.charAt(0)); break; }
            else throw new YamlException(line + 1, "Ungültiger Blocktext-Kopf: " + header);
        }
        int contentInd = explicit > 0 ? Math.max(ind, 0) + explicit : -1;
        List<String> body = new ArrayList<>();
        while (pos < lines.length) {
            String l = lines[pos];
            if (l.isBlank()) {
                body.add(contentInd > 0 && l.length() > contentInd ? l.substring(contentInd) : "");
                pos++;
                continue;
            }
            int i = 0;
            while (i < l.length() && l.charAt(i) == ' ') i++;
            if (contentInd < 0) {
                if (i <= ind) break;
                contentInd = i;
            }
            if (i < contentInd) break;
            body.add(l.substring(contentInd));
            pos++;
        }
        // nachgestellte Leerzeilen gehören je nach Chomping zum Text oder nicht
        int trailing = 0;
        while (trailing < body.size() && body.get(body.size() - 1 - trailing).isEmpty()) trailing++;
        List<String> content = body.subList(0, body.size() - trailing);
        StringBuilder sb = new StringBuilder();
        if (literal) {
            for (int i = 0; i < content.size(); i++) { if (i > 0) sb.append('\n'); sb.append(content.get(i)); }
        } else {
            boolean prevText = false;
            for (int i = 0; i < content.size(); i++) {
                String l = content.get(i);
                boolean more = l.startsWith(" ") || l.startsWith("\t");
                if (i > 0) {
                    if (l.isEmpty()) sb.append('\n');
                    else if (prevText && !more && !content.get(i - 1).isEmpty()) sb.append(' ');
                    else if (!content.get(i - 1).isEmpty() || more) sb.append('\n');
                }
                sb.append(l);
                prevText = !l.isEmpty() && !more;
            }
        }
        if (content.isEmpty()) return chomp == '+' ? "\n".repeat(trailing) : "";
        if (chomp == 'c') sb.append('\n');
        else if (chomp == '+') sb.append("\n".repeat(trailing + 1));
        return sb.toString();
    }

    // ------------------------------------------------------------------ Flow-Collections

    private static final class Flow {
        final String s;
        final int line;
        int i;
        Flow(String s, int line) { this.s = s; this.line = line; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            ws();
            if (i >= s.length()) throw new YamlException(line, "Wert fehlt");
            char c = s.charAt(i);
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (peek(',')) { i++; ws(); if (peek(']')) { i++; return l; } continue; }
                    if (peek(']')) { i++; return l; }
                    throw new YamlException(line, "„,“ oder „]“ erwartet");
                }
            }
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) { i++; return m; }
                while (true) {
                    ws();
                    Object k = scalar(true);
                    ws();
                    Object v = null;
                    if (peek(':')) { i++; v = value(); }
                    m.put(k == null ? "null" : String.valueOf(k), v);
                    ws();
                    if (peek(',')) { i++; ws(); if (peek('}')) { i++; return m; } continue; }
                    if (peek('}')) { i++; return m; }
                    throw new YamlException(line, "„,“ oder „}“ erwartet");
                }
            }
            return scalar(false);
        }

        Object scalar(boolean key) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int end = quoteEnd(s, i);
                if (end < 0) throw new YamlException(line, "Anführungszeichen nicht geschlossen");
                String q = s.substring(i, end + 1);
                i = end + 1;
                return unquote(q, line);
            }
            int start = i;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == ',' || ch == ']' || ch == '}') break;
                if (ch == ':' && (key || i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1)) || ",]}".indexOf(s.charAt(i + 1)) >= 0)) break;
                i++;
            }
            String t = s.substring(start, i).trim();
            return key ? t : resolve(t);
        }

        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
    }

    private static boolean balanced(CharSequence s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int e = quoteEnd(s, i);
                if (e < 0) return false;
                i = e;
            } else if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') depth--;
        }
        return depth <= 0;
    }

    // ------------------------------------------------------------------ Skalare

    /** Position des schließenden Anführungszeichens zu dem bei {@code start}, oder -1. */
    private static int quoteEnd(CharSequence s, int start) {
        char q = s.charAt(start);
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q == '"' && c == '\\') { i++; continue; }
            if (c == q) {
                if (q == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') { i++; continue; }
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String q, int line) {
        String body = q.substring(1, q.length() - 1);
        if (q.charAt(0) == '\'') return fold(body).replace("''", "'");
        StringBuilder sb = new StringBuilder();
        String b = foldDouble(body);
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (c != '\\') { sb.append(c); continue; }
            if (++i >= b.length()) throw new YamlException(line, "Backslash am Ende");
            char e = b.charAt(i);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't', '\t' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '0' -> sb.append('\0');
                case 'a' -> sb.append('\u0007');
                case 'b' -> sb.append('\b');
                case 'e' -> sb.append('\u001B');
                case 'f' -> sb.append('\f');
                case 'v' -> sb.append('\u000B');
                case ' ' -> sb.append(' ');
                case '"', '\\', '/' -> sb.append(e);
                case 'N' -> sb.append('\u0085');
                case '_' -> sb.append(' ');
                case 'x', 'u', 'U' -> {
                    int n = e == 'x' ? 2 : e == 'u' ? 4 : 8;
                    if (i + n >= b.length()) throw new YamlException(line, "Unvollständige Escape-Sequenz \\" + e);
                    try {
                        sb.appendCodePoint(Integer.parseInt(b.substring(i + 1, i + 1 + n), 16));
                    } catch (RuntimeException ex) {
                        throw new YamlException(line, "Ungültige Escape-Sequenz \\" + e);
                    }
                    i += n;
                }
                case '\n' -> { /* Zeilenumbruch maskiert: entfällt */ }
                default -> throw new YamlException(line, "Unbekannte Escape-Sequenz \\" + e);
            }
        }
        return sb.toString();
    }

    /** Mehrzeilige Anführungszeichen-Texte: einfacher Umbruch wird Leerzeichen, Leerzeile wird Umbruch. */
    private static String fold(String s) {
        if (s.indexOf('\n') < 0) return s;
        String[] parts = s.split("\n", -1);
        StringBuilder sb = new StringBuilder(parts[0].stripTrailing());
        boolean pendingSpace = true;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].strip();
            if (p.isEmpty() && i < parts.length - 1) { sb.append('\n'); pendingSpace = false; continue; }
            if (pendingSpace) sb.append(' ');
            sb.append(i < parts.length - 1 ? p : parts[i].stripLeading());
            pendingSpace = true;
        }
        return sb.toString();
    }

    private static String foldDouble(String s) {
        if (s.indexOf('\n') < 0) return s;
        // "\" am Zeilenende maskiert den Umbruch: als "\\\n" stehen lassen, unquote entfernt ihn
        String[] parts = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = i == 0 ? parts[i] : parts[i].stripLeading();
            if (i < parts.length - 1) {
                int bs = 0;
                for (int k = p.length() - 1; k >= 0 && p.charAt(k) == '\\'; k--) bs++;
                if (bs % 2 == 1) { sb.append(p, 0, p.length() - 1); continue; }
                sb.append(p.stripTrailing());
                String next = parts[i + 1].strip();
                if (next.isEmpty() && i + 1 < parts.length - 1) { sb.append("\\n"); i++; while (i + 1 < parts.length - 1 && parts[i + 1].isBlank()) { sb.append("\\n"); i++; } continue; }
                sb.append(' ');
            } else {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** Löst einen einfachen (nicht in Anführungszeichen stehenden) Skalar auf. */
    static Object resolve(String t) {
        if (t.isEmpty() || t.equals("~") || t.equals("null") || t.equals("Null") || t.equals("NULL")) return null;
        if (BOOL_TRUE.matcher(t).matches()) return Boolean.TRUE;
        if (BOOL_FALSE.matcher(t).matches()) return Boolean.FALSE;
        if (isNumber(t)) return new Json.JsonNumber(t);
        return t;
    }

    static boolean isNumber(String t) {
        return INT.matcher(t).matches() || (FLOAT.matcher(t).matches() && t.chars().anyMatch(Character::isDigit))
                || t.matches("[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)");
    }

    // ------------------------------------------------------------------ Zeilen

    private static String key(String k, int line) {
        if (k.isEmpty()) throw new YamlException(line + 1, "Leerer Schlüssel");
        char c = k.charAt(0);
        if (c == '"' || c == '\'') {
            int e = quoteEnd(k, 0);
            if (e != k.length() - 1) throw new YamlException(line + 1, "Ungültiger Schlüssel: " + k);
            return unquote(k, line + 1);
        }
        if (c == '?' ) throw new YamlException(line + 1, "Komplexe Schlüssel (?) werden nicht unterstützt");
        if (c == '&' || c == '*' || c == '!') throw new YamlException(line + 1, "Anker, Aliase und Tags werden nicht unterstützt");
        return k;
    }

    /** Position des ':' hinter einem Schlüssel (gefolgt von Leerzeichen oder Zeilenende), oder -1. */
    private static int keyEnd(String t) {
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("[") || t.startsWith("{") || t.startsWith("|") || t.startsWith(">")) return -1;
        int i = 0;
        char c = t.charAt(0);
        if (c == '"' || c == '\'') {
            int e = quoteEnd(t, 0);
            if (e < 0) return -1;
            i = e + 1;
            while (i < t.length() && t.charAt(i) == ' ') i++;
            return i < t.length() && t.charAt(i) == ':' && (i + 1 == t.length() || Character.isWhitespace(t.charAt(i + 1))) ? i : -1;
        }
        for (; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '#' && i > 0 && Character.isWhitespace(t.charAt(i - 1))) return -1;
            if (ch == ':' && (i + 1 == t.length() || Character.isWhitespace(t.charAt(i + 1)))) return i;
        }
        return -1;
    }

    /** Beginnt der Text eines Listeneintrags mit einem Skalar in Anführungszeichen, der kein Schlüssel ist? */
    private static boolean startsInline(String rest) {
        char c = rest.charAt(0);
        return (c == '"' || c == '\'') && keyEnd(rest) < 0;
    }

    private static boolean isSeqItem(String t) {
        return t.equals("-") || t.startsWith("- ") || t.startsWith("-\t");
    }

    private static boolean isComment(String l) {
        return l.trim().startsWith("#");
    }

    private static boolean isDocEnd(String l) {
        return l.startsWith("---") || l.startsWith("...");
    }

    /** Entfernt einen Kommentar (# nach Leerzeichen, außerhalb von Anführungszeichen) und Leerzeichen am Ende. */
    private static String strip(String s) {
        boolean sq = false, dq = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (dq && c == '\\') { i++; continue; }
            if (c == '"' && !sq) dq = !dq;
            else if (c == '\'' && !dq) sq = !sq;
            else if (c == '#' && !sq && !dq && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) return s.substring(0, i).stripTrailing();
        }
        return s.stripTrailing();
    }

    private void skipBlank() {
        while (pos < lines.length && (lines[pos].isBlank() || isComment(lines[pos]))) pos++;
    }

    private static int indent(String l, int line) {
        int i = 0;
        while (i < l.length() && l.charAt(i) == ' ') i++;
        if (i < l.length() && l.charAt(i) == '\t') throw new YamlException(line + 1, "Tabulator zur Einrückung ist in YAML nicht erlaubt");
        return i;
    }
}
