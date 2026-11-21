package jsonlogviewer.format.springboot;

import jsonlogviewer.format.pattern.PatternLogFormat;

/** Standard-Konsolen- und Dateiformat von Spring Boot 3.x und 2.x. */
public class SpringBootFormat extends PatternLogFormat {

    public SpringBootFormat() {
        super("spring-boot", "Spring Boot",
                "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p %pid --- %esb{application}[%15.15t] %correlationId%-40.40logger{39} : %m%n",
                "%d{yyyy-MM-dd HH:mm:ss.SSS} %5p %pid --- [%15.15t] %-40.40logger{39} : %m%n");
    }
}
