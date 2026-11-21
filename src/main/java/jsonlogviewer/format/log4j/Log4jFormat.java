package jsonlogviewer.format.log4j;

import jsonlogviewer.format.pattern.PatternLogFormat;

/** Log4j 2 und Log4j 1.x mit verbreiteten PatternLayouts (auch JBoss/WildFly). */
public class Log4jFormat extends PatternLogFormat {

    public Log4jFormat() {
        super("log4j", "Log4j / Log4j2",
                "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n",
                "%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n",
                "%d %p %c{1.} [%t] %m%n",
                "%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n",
                "%d{ISO8601} %-5p [%t] %c - %m%n",
                "%d{ISO8601} [%t] %-5p %c %x - %m%n",
                "%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c] (%t) %m%n",
                "%d{HH:mm:ss,SSS} %-5p [%c] (%t) %m%n",
                "%r [%t] %-5p %c %x - %m%n");
    }
}
