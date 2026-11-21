package jsonlogviewer;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/**
 * Stellt ein Manifest (Typen wie bei {@link Json#parse}) als XML dar und liest es zurück.
 * Die Struktur steckt in den Elementen, nicht in der Einrückung; beim Schreiben wird automatisch eingerückt.
 * <ul>
 * <li>Mapping: Kind-Elemente je Schlüssel. Schlüssel, die kein gültiger XML-Name sind
 *     (z.B. {@code app.kubernetes.io/name}), als {@code <entry key="…">}. Leeres Mapping: {@code type="map"}.</li>
 * <li>Liste: {@code type="list"} mit {@code <item>}-Kindern.</li>
 * <li>Skalar: Text, aufgelöst wie ein YAML-Skalar ({@code 3} Zahl, {@code true} Wahrheitswert, leer = null).
 *     Texte, die sonst als Zahl usw. gelesen würden, tragen {@code type="string"}.</li>
 * </ul>
 */
final class XmlView {

    static final String ROOT = "manifest";
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private XmlView() { }

    // ------------------------------------------------------------------ schreiben

    static String toXml(Object v) {
        StringBuilder sb = new StringBuilder();
        element(sb, ROOT, null, v, 0, "/" + ROOT);
        return sb.toString();
    }

    private static void element(StringBuilder sb, String tag, String key, Object v, int ind, String path) {
        String pad = " ".repeat(ind);
        sb.append(pad).append('<').append(tag);
        if (key != null) sb.append(" key=\"").append(escAttr(key, path)).append('"');
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append(" type=\"map\"/>\n"); return; }
            sb.append(">\n");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (validName(k)) element(sb, k, null, e.getValue(), ind + 2, path + "/" + k);
                else element(sb, "entry", k, e.getValue(), ind + 2, path + "/" + k);
            }
            sb.append(pad).append("</").append(tag).append(">\n");
        } else if (v instanceof List<?> l) {
            sb.append(" type=\"list\"");
            if (l.isEmpty()) { sb.append("/>\n"); return; }
            sb.append(">\n");
            int i = 0;
            for (Object o : l) element(sb, "item", null, o, ind + 2, path + "[" + i++ + "]");
            sb.append(pad).append("</").append(tag).append(">\n");
        } else if (v == null) {
            sb.append("/>\n");
        } else if (v instanceof Boolean || v instanceof Json.JsonNumber) {
            String t = v.toString();
            if (v instanceof Json.JsonNumber && !YamlReader.isNumber(t)) sb.append(" type=\"number\"");
            sb.append('>').append(escText(t, path)).append("</").append(tag).append(">\n");
        } else {
            String s = v.toString();
            if (s.isEmpty() || !(YamlReader.resolve(s.trim()) instanceof String)) sb.append(" type=\"string\"");
            if (s.isEmpty()) sb.append("/>\n");
            else sb.append('>').append(escText(s, path)).append("</").append(tag).append(">\n");
        }
    }

    static boolean validName(String k) {
        return NAME.matcher(k).matches() && !k.regionMatches(true, 0, "xml", 0, 3);
    }

    private static String escText(String s, String path) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\r' -> sb.append("&#13;");
                default -> { checkChar(c, path); sb.append(c); }
            }
        }
        return sb.toString();
    }

    private static String escAttr(String s, String path) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '"' -> sb.append("&quot;");
                case '\n' -> sb.append("&#10;");
                case '\r' -> sb.append("&#13;");
                case '\t' -> sb.append("&#9;");
                default -> { checkChar(c, path); sb.append(c); }
            }
        }
        return sb.toString();
    }

    private static void checkChar(char c, String path) {
        if ((c < 0x20 && c != '\t' && c != '\n') || c == 0xFFFE || c == 0xFFFF)
            throw new IllegalArgumentException(String.format("%s: Steuerzeichen U+%04X lässt sich in XML nicht darstellen", path, (int) c));
    }

    // ------------------------------------------------------------------ lesen

    static Object fromXml(String xml) {
        Element root;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            f.setXIncludeAware(false);
            f.setNamespaceAware(false);
            f.setCoalescing(true);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) { }
                @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
            });
            root = b.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (SAXParseException e) {
            throw new IllegalArgumentException("Zeile " + e.getLineNumber() + ", Spalte " + e.getColumnNumber() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException(String.valueOf(e.getMessage()), e);
        }
        return value(root, "/" + root.getTagName());
    }

    private static Object value(Element e, String path) {
        String type = e.getAttribute("type");
        List<Element> children = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            switch (n.getNodeType()) {
                case Node.ELEMENT_NODE -> children.add((Element) n);
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(n.getNodeValue());
                default -> { } // Kommentare, Verarbeitungsanweisungen
            }
        }
        boolean hasText = !text.toString().isBlank();
        switch (type) {
            case "list" -> {
                if (hasText) throw err(path, "Liste enthält Text außerhalb von <item>");
                List<Object> l = new ArrayList<>();
                int i = 0;
                for (Element c : children) {
                    if (!c.getTagName().equals("item")) throw err(path, "In einer Liste sind nur <item>-Elemente erlaubt, nicht <" + c.getTagName() + ">");
                    l.add(value(c, path + "[" + i++ + "]"));
                }
                return l;
            }
            case "map" -> {
                if (hasText) throw err(path, "Mapping enthält Text");
                return map(children, path);
            }
            case "string" -> {
                if (!children.isEmpty()) throw err(path, "type=\"string\" darf keine Elemente enthalten");
                return text.toString();
            }
            case "number" -> {
                noChildren(children, path);
                String t = text.toString().trim();
                if (!YamlReader.isNumber(t)) throw err(path, "Keine Zahl: " + t);
                return new Json.JsonNumber(t);
            }
            case "bool" -> {
                noChildren(children, path);
                Object b = YamlReader.resolve(text.toString().trim());
                if (!(b instanceof Boolean)) throw err(path, "Kein Wahrheitswert: " + text.toString().trim());
                return b;
            }
            case "null" -> {
                noChildren(children, path);
                if (hasText) throw err(path, "type=\"null\" darf keinen Text enthalten");
                return null;
            }
            case "" -> {
                if (!children.isEmpty()) {
                    if (hasText) throw err(path, "Text und Elemente gemischt: „" + text.toString().trim() + "“");
                    return map(children, path);
                }
                Object r = YamlReader.resolve(text.toString().trim());
                return r instanceof String ? text.toString() : r;
            }
            default -> throw err(path, "Unbekannter type=\"" + type + "\" (erlaubt: map, list, string, number, bool, null)");
        }
    }

    private static Map<String, Object> map(List<Element> children, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Element c : children) {
            String key = c.getTagName().equals("entry") && c.hasAttribute("key") ? c.getAttribute("key") : c.getTagName();
            String p = path + "/" + key;
            if (m.containsKey(key)) throw err(p, "Schlüssel doppelt");
            m.put(key, value(c, p));
        }
        return m;
    }

    private static void noChildren(List<Element> children, String path) {
        if (!children.isEmpty()) throw err(path, "Darf keine Elemente enthalten");
    }

    private static IllegalArgumentException err(String path, String msg) {
        return new IllegalArgumentException(path + ": " + msg);
    }
}
