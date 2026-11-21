package jsonlogviewer.format.klog;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsonlogviewer.format.AbstractLogFormat;
import jsonlogviewer.format.Fields;

/**
 * klog/glog, das Format der Kubernetes-Komponenten (kubelet, Controller, Operatoren):
 * {@code I1121 10:15:30.123456   12345 controller.go:123] Nachricht}. Das Jahr fehlt im Log; angenommen wird das
 * laufende (bzw. das vorige, wenn der Monat in der Zukunft läge). Die Zeit ist UTC.
 */
public class KlogFormat extends AbstractLogFormat {

    private static final Pattern LINE = Pattern.compile(
            "^([IWEF])(\\d{2})(\\d{2}) (\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?)\\s+(\\d+) ([^:\\]\\s]+):(\\d+)\\] ?(.*)$");

    public KlogFormat() {
        super("klog", "klog (Kubernetes)");
    }

    @Override public String description() { return "I1121 10:15:30.123456   12345 datei.go:123] Nachricht"; }

    @Override public boolean isEntryStart(String line) {
        return LINE.matcher(line).matches();
    }

    @Override protected Map<String, Object> parseLine(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return null;
        int month = Integer.parseInt(m.group(2)), day = Integer.parseInt(m.group(3));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int year = month > today.getMonthValue() ? today.getYear() - 1 : today.getYear();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(Fields.TIMESTAMP, isoTimestamp(String.format("%04d-%02d-%02dT%s", year, month, day, m.group(4)), null, ZoneOffset.UTC));
        out.put(Fields.LEVEL, switch (m.group(1)) {
            case "W" -> "WARN";
            case "E" -> "ERROR";
            case "F" -> "FATAL";
            default -> "INFO";
        });
        out.put(Fields.THREAD_ID, m.group(5));
        out.put(Fields.FILE, m.group(6));
        out.put(Fields.LINE, m.group(7));
        out.put(Fields.MESSAGE, m.group(8));
        return out;
    }
}
