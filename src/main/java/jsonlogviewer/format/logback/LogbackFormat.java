package jsonlogviewer.format.logback;

import jsonlogviewer.format.pattern.PatternLogFormat;

/** Logback mit den Mustern aus Standardkonfiguration und Dokumentation. Eigene Muster: Schema → Import → logback.xml. */
public class LogbackFormat extends PatternLogFormat {

    public LogbackFormat() {
        super("logback", "Logback",
                "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n",
                "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} -%kvp- %msg%n",
                "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n",
                "%d [%thread] %-5level %logger{35} - %msg%n",
                "%date %level [%thread] %logger{10} [%file:%line] %msg%n",
                "%-4relative [%thread] %-5level %logger{35} - %msg%n");
    }
}
