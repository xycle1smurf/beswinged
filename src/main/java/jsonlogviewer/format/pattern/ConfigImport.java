package jsonlogviewer.format.pattern;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Liest Appender und ihre Layout-Muster aus {@code logback.xml} (auch {@code logback-spring.xml}) bzw.
 * {@code log4j2.xml}. Variablen ({@code <property>}, {@code <Property>}, Spring-Boot-Standards) werden aufgelöst.
 */
public final class ConfigImport {

    /** Ein Appender bzw. Muster zur Auswahl. {@code pattern} leer: kein Textmuster (z.B. JSON-Encoder). */
    public record Candidate(String name, String type, String pattern, String note) {
        public boolean usable() { return !pattern.isBlank(); }
    }

    /** Ergebnis: Muster sind schon aufgelöst; {@code properties} nur zur Anzeige. */
    public record Result(List<Candidate> candidates, Map<String, String> properties) { }

    static final String SPRING_CONSOLE = "%clr(%d{${LOG_DATEFORMAT_PATTERN:-yyyy-MM-dd'T'HH:mm:ss.SSSXXX}}){faint} "
            + "%clr(${LOG_LEVEL_PATTERN:-%5p}) %clr(${PID:- }){magenta} %clr(--- %esb(){APPLICATION_NAME}%esb{APPLICATION_GROUP}"
            + "[%15.15t] ${LOG_CORRELATION_PATTERN:-}){faint}%clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n"
            + "${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}";
    static final String SPRING_FILE = "%d{${LOG_DATEFORMAT_PATTERN:-yyyy-MM-dd'T'HH:mm:ss.SSSXXX}} ${LOG_LEVEL_PATTERN:-%5p} "
            + "${PID:- } --- %esb(){APPLICATION_NAME}%esb{APPLICATION_GROUP}[%t] ${LOG_CORRELATION_PATTERN:-}"
            + "%-40.40logger{39} : %m%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}";

    private ConfigImport() { }

    // ------------------------------------------------------------------ logback.xml

    public static Result logback(Path xml) throws IOException {
        Document doc = parse(xml);
        Map<String, String> props = new LinkedHashMap<>();
        boolean spring = false;
        for (Element e : all(doc.getDocumentElement())) {
            String tag = local(e);
            if (tag.equals("property") || tag.equals("variable")) {
                if (!e.getAttribute("name").isBlank() && e.hasAttribute("value")) props.putIfAbsent(e.getAttribute("name"), e.getAttribute("value"));
            } else if (tag.equals("springProperty")) {
                if (!e.getAttribute("name").isBlank()) props.putIfAbsent(e.getAttribute("name"), e.getAttribute("defaultValue"));
            } else if (tag.equals("include") && e.getAttribute("resource").contains("org/springframework/boot/logging/logback")) {
                spring = true;
            }
        }
        if (spring) {
            props.putIfAbsent("CONSOLE_LOG_PATTERN", SPRING_CONSOLE);
            props.putIfAbsent("FILE_LOG_PATTERN", SPRING_FILE);
        }
        List<Candidate> out = new ArrayList<>();
        for (Element a : all(doc.getDocumentElement())) {
            if (!local(a).equals("appender")) continue;
            String name = a.getAttribute("name"), cls = a.getAttribute("class");
            String type = cls.substring(cls.lastIndexOf('.') + 1);
            String pattern = "";
            String encoder = "";
            for (Element c : all(a)) {
                String tag = local(c);
                if ((tag.equals("encoder") || tag.equals("layout")) && encoder.isEmpty()) encoder = c.getAttribute("class");
                if (tag.equals("pattern") && pattern.isEmpty()) pattern = c.getTextContent().trim();
            }
            if (!pattern.isEmpty()) {
                out.add(new Candidate(name, type, LayoutPattern.resolveVariables(pattern, props), ""));
            } else {
                String enc = encoder.substring(encoder.lastIndexOf('.') + 1);
                String note = enc.toLowerCase(Locale.ROOT).contains("json") || enc.contains("Logstash") || enc.contains("Ecs")
                        ? "JSON-Encoder (" + enc + "): Log ist JSON, kein Muster nötig"
                        : type.contains("Async") ? "leitet an andere Appender weiter" : "kein Muster gefunden" + (enc.isEmpty() ? "" : " (" + enc + ")");
                out.add(new Candidate(name, type, "", note));
            }
        }
        if (spring) {
            out.add(new Candidate("Spring Boot Konsole", "defaults.xml", LayoutPattern.resolveVariables(props.get("CONSOLE_LOG_PATTERN"), props),
                    "aus dem eingebundenen Spring-Boot-Standard"));
            out.add(new Candidate("Spring Boot Datei", "defaults.xml", LayoutPattern.resolveVariables(props.get("FILE_LOG_PATTERN"), props),
                    "aus dem eingebundenen Spring-Boot-Standard"));
        }
        return new Result(out, props);
    }

    // ------------------------------------------------------------------ log4j2.xml

    public static Result log4j2(Path xml) throws IOException {
        Document doc = parse(xml);
        Map<String, String> props = new LinkedHashMap<>();
        for (Element e : all(doc.getDocumentElement())) {
            if (local(e).equalsIgnoreCase("Property") && !e.getAttribute("name").isBlank())
                props.putIfAbsent(e.getAttribute("name"), e.hasAttribute("value") ? e.getAttribute("value") : e.getTextContent().trim());
        }
        List<Candidate> out = new ArrayList<>();
        Element appenders = null;
        for (Element e : all(doc.getDocumentElement())) if (local(e).equalsIgnoreCase("Appenders")) { appenders = e; break; }
        if (appenders == null) return new Result(out, props);
        for (Element a : children(appenders)) {
            String name = a.getAttribute("name");
            String type = local(a).equalsIgnoreCase("Appender") ? a.getAttribute("type") : local(a);
            String pattern = "", layout = "";
            for (Element c : all(a)) {
                String tag = local(c);
                if (tag.endsWith("Layout") && layout.isEmpty()) layout = tag;
                if (tag.equalsIgnoreCase("PatternLayout") && pattern.isEmpty()) {
                    pattern = c.getAttribute("pattern");
                    if (pattern.isEmpty())
                        for (Element p : children(c)) if (local(p).equalsIgnoreCase("Pattern")) pattern = p.getTextContent().trim();
                }
                if (tag.equalsIgnoreCase("Layout") && "PatternLayout".equalsIgnoreCase(c.getAttribute("type")) && pattern.isEmpty())
                    pattern = c.getAttribute("pattern");
            }
            if (!pattern.isEmpty()) out.add(new Candidate(name, type, LayoutPattern.resolveVariables(pattern, props), ""));
            else out.add(new Candidate(name, type, "", layout.toLowerCase(Locale.ROOT).contains("json") || layout.contains("Ecs")
                    ? "JSON-Layout (" + layout + "): Log ist JSON, kein Muster nötig"
                    : layout.isEmpty() ? "kein PatternLayout (Standard: %m%n)" : "kein Muster (" + layout + ")"));
        }
        return new Result(out, props);
    }

    // ------------------------------------------------------------------ XML

    private static Document parse(Path xml) throws IOException {
        try (InputStream in = Files.newInputStream(xml)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            try {
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            } catch (ParserConfigurationException | IllegalArgumentException ignored) {
                // Parser kennt die Option nicht
            }
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(in);
        } catch (ParserConfigurationException | SAXException ex) {
            throw new IOException("Keine gültige XML-Datei: " + ex.getMessage(), ex);
        }
    }

    private static String local(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
    }

    /** Alle Elemente unterhalb (Tiefensuche, in Dokumentreihenfolge). */
    private static List<Element> all(Element root) {
        List<Element> l = new ArrayList<>();
        NodeList nl = root.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) l.add((Element) nl.item(i));
        return l;
    }

    private static List<Element> children(Element e) {
        List<Element> l = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element c) l.add(c);
        return l;
    }
}
