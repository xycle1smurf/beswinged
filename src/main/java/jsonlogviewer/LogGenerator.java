package jsonlogviewer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Erzeugt Beispiel-Logs (JSON Lines) mit verschiedenen Levels, Meldungen und Stacktraces
 * und aufeinanderfolgenden Zeitstempeln.
 *
 * <ul>
 *   <li>ohne Schema: Format von logstash-logback-encoder ({@code @timestamp, message, logger_name, …})</li>
 *   <li>mit Schema: jede Spalte des Schemas wird anhand ihres Pfads mit einem passenden Wert befüllt</li>
 * </ul>
 *
 * Kommandozeile: {@code java -cp beswinged.jar jsonlogviewer.LogGenerator ziel.jsonl [zeilen] [schema.yaml]}
 */
public final class LogGenerator {

    /** Ein erzeugtes Ereignis, unabhängig vom Ausgabeformat. */
    record Event(String timestamp, String level, String logger, String thread, String message, String stack,
                 String traceId, String spanId, String userId, int durationMs) {}

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final String[][] USERS = {{"u-1001", "anna"}, {"u-1002", "ben"}, {"u-1003", "carla"}, {"u-1004", "deniz"}, {"u-1005", "emil"}};
    private static final String HOST = "shop-prod-02";
    private static final String APP = "shop-service";

    private final Random r;
    private final Instant start;
    private final int intervalSeconds;
    private final ZoneId zone;

    public LogGenerator(Instant start, int intervalSeconds, long seed) {
        this.r = new Random(seed);
        this.start = start;
        this.intervalSeconds = intervalSeconds;
        this.zone = ZoneId.systemDefault();
    }

    /**
     * Schreibt {@code count} Zeilen nach {@code out}.
     * @param schema   null = Logstash-Format, sonst passend zu den Spalten des Schemas
     * @param progress erhält die Anzahl geschriebener Zeilen (gelegentlich), darf null sein
     * @param cancel   Abbruch, darf null sein
     */
    public void write(Path out, int count, Schema schema, IntConsumer progress, BooleanSupplier cancel) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < count; i++) {
                if ((i & 0x3FF) == 0) {
                    if (cancel != null && cancel.getAsBoolean()) return;
                    if (progress != null) progress.accept(i);
                }
                Event e = event(i);
                w.write(Json.stringify(schema == null ? logstash(e) : forSchema(e, schema), false));
                w.write('\n');
            }
        }
        if (progress != null) progress.accept(count);
    }

    // ---------------------------------------------------------------- Ausgabe

    private static Map<String, Object> logstash(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("@timestamp", e.timestamp());
        m.put("@version", "1");
        m.put("message", e.message());
        m.put("logger_name", e.logger());
        m.put("thread_name", e.thread());
        m.put("level", e.level());
        m.put("level_value", new Json.JsonNumber(String.valueOf(levelValue(e.level()))));
        if (e.stack() != null) m.put("stack_trace", e.stack());
        if (e.traceId() != null) {
            m.put("traceId", e.traceId());
            m.put("spanId", e.spanId());
            m.put("userId", e.userId());
        }
        m.put("app", APP);
        m.put("host", HOST);
        return m;
    }

    /** Befüllt jeden Spaltenpfad des Schemas mit einem zur Bezeichnung passenden Wert. */
    private Map<String, Object> forSchema(Event e, Schema schema) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Schema.Column c : schema.columns) {
            if (c.isRaw() || c.segments().length == 0) continue;
            Object v = valueFor(c.segments(), e);
            if (v != null) put(root, c.segments(), v);
        }
        return root;
    }

    private Object valueFor(String[] segs, Event e) {
        String path = String.join(".", segs).toLowerCase(Locale.ROOT);
        String last = segs[segs.length - 1].toLowerCase(Locale.ROOT);
        if (path.contains("timestamp") || last.equals("time") || last.equals("ts") || last.equals("date")
                || last.equals("datetime") || last.equals("@t")) return e.timestamp();
        if (last.equals("level_value")) return num(levelValue(e.level()));
        if (last.contains("level") || last.equals("severity")) return e.level();
        if (last.contains("logger") || last.equals("category") || last.equals("class")) return e.logger();
        if (last.contains("thread")) return e.thread();
        if (last.contains("stack")) return e.stack();
        if (path.contains("exception") || path.startsWith("error")) {
            if (e.stack() == null) return null;
            String first = e.stack().lines().findFirst().orElse("");
            int colon = first.indexOf(':');
            return last.contains("message") && colon > 0 ? first.substring(colon + 1).trim()
                    : colon > 0 ? first.substring(0, colon) : first;
        }
        if (last.startsWith("msg") || last.contains("message") || last.equals("text")) return e.message();
        if (path.contains("trace")) return e.traceId();
        if (path.contains("span")) return e.spanId();
        if (path.contains("user")) return e.userId();
        if (last.contains("duration") || last.contains("elapsed") || last.endsWith("ms")) return num(e.durationMs());
        if (last.contains("host")) return HOST;
        if (last.equals("app") || last.contains("service") || last.contains("application")) return APP;
        if (last.contains("version")) return "1";
        if (last.contains("pid")) return num(4711);
        return List.of("alpha", "beta", "gamma", "delta").get(r.nextInt(4));
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String[] segs, Object v) {
        Map<String, Object> m = root;
        for (int i = 0; i < segs.length - 1; i++) {
            Object child = m.get(segs[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                m.put(segs[i], child);
            }
            m = (Map<String, Object>) child;
        }
        m.put(segs[segs.length - 1], v);
    }

    private static Json.JsonNumber num(int n) { return new Json.JsonNumber(String.valueOf(n)); }

    // ---------------------------------------------------------------- Ereignisse

    private static final String[][] STARTUP = {
        {"INFO", "de.example.shop.ShopApplication", "Starting ShopApplication v2.4.1 using Java 21.0.5 with PID 4711"},
        {"DEBUG", "org.springframework.boot.context.config.ConfigDataEnvironment", "Loaded config file 'application.yaml' and profile 'prod'"},
        {"INFO", "com.zaxxer.hikari.HikariDataSource", "HikariPool-1 - Starting..."},
        {"INFO", "com.zaxxer.hikari.HikariDataSource", "HikariPool-1 - Start completed."},
        {"WARN", "org.hibernate.orm.deprecation", "HHH90000025: PostgreSQLDialect does not need to be specified explicitly"},
        {"INFO", "org.springframework.boot.web.embedded.tomcat.TomcatWebServer", "Tomcat started on port 8080 (http) with context path '/'"},
        {"INFO", "de.example.shop.ShopApplication", "Started ShopApplication in 7.312 seconds (process running for 8.04)"},
    };

    Event event(int i) {
        String ts = TS.format(start.plusSeconds((long) intervalSeconds * i).atZone(zone));
        int duration = 20 + r.nextInt(900);
        if (i < STARTUP.length) {
            String[] s = STARTUP[i];
            return new Event(ts, s[0], s[1], "main", s[2], null, null, null, null, duration);
        }
        String[] u = USERS[r.nextInt(USERS.length)];
        int order = 50_000 + i;
        String[] ev = randomEvent(u[1], order, duration);
        boolean scheduled = ev[1].contains("Scheduler") || ev[1].contains("Cache");
        String thread = scheduled ? "scheduling-1" : "http-nio-8080-exec-" + (1 + r.nextInt(10));
        String trace = hex(32), span = hex(16);
        return scheduled
                ? new Event(ts, ev[0], ev[1], thread, ev[2], ev[3], null, null, null, duration)
                : new Event(ts, ev[0], ev[1], thread, ev[2], ev[3], trace, span, u[0], duration);
    }

    /** {level, logger, message, stack} */
    private String[] randomEvent(String user, int order, int ms) {
        int p = r.nextInt(100);
        String amount = String.format(Locale.ROOT, "%.2f", 5 + r.nextDouble() * 400);
        if (p < 8) return ev("TRACE", "de.example.shop.web.RequestLoggingFilter", "GET /api/products?page=" + r.nextInt(20) + " headers={accept=application/json}");
        if (p < 25) return switch (r.nextInt(4)) {
            case 0 -> ev("DEBUG", "de.example.shop.cart.CartService", "Cart of user '" + user + "' contains " + (1 + r.nextInt(8)) + " items");
            case 1 -> ev("DEBUG", "de.example.shop.catalog.ProductCache", "Cache hit ratio " + (70 + r.nextInt(30)) + "% (size=" + (900 + r.nextInt(300)) + ")");
            case 2 -> ev("DEBUG", "org.hibernate.SQL", "select o.id, o.status, o.total from orders o where o.user_id=?");
            default -> ev("DEBUG", "de.example.shop.payment.PaymentClient", "POST https://psp.example.com/v1/charges -> 201 in " + ms + " ms");
        };
        if (p < 75) return switch (r.nextInt(5)) {
            case 0 -> ev("INFO", "de.example.shop.order.OrderService", "Order " + order + " created for user '" + user + "', total " + amount + " EUR");
            case 1 -> ev("INFO", "de.example.shop.payment.PaymentService", "Payment for order " + order + " authorized (" + amount + " EUR)");
            case 2 -> ev("INFO", "de.example.shop.auth.LoginController", "User '" + user + "' logged in");
            case 3 -> ev("INFO", "de.example.shop.shipping.ShippingService", "Order " + order + " handed over to carrier DHL, tracking 00340434" + (100000 + order));
            default -> ev("INFO", "de.example.shop.order.OrderScheduler", "Nightly cleanup removed " + r.nextInt(40) + " abandoned carts");
        };
        if (p < 92) return switch (r.nextInt(4)) {
            case 0 -> ev("WARN", "de.example.shop.payment.PaymentClient", "Slow response from payment provider: " + (2000 + r.nextInt(3000)) + " ms (threshold 2000 ms)");
            case 1 -> ev("WARN", "de.example.shop.auth.LoginController", "Failed login attempt for user '" + user + "' (attempt " + (1 + r.nextInt(4)) + " of 5)");
            case 2 -> ev("WARN", "com.zaxxer.hikari.pool.HikariPool", "HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=" + (30 + r.nextInt(30)) + "s)");
            default -> ev("WARN", "de.example.shop.catalog.StockService", "Stock for product SKU-" + (1000 + r.nextInt(500)) + " below threshold: " + r.nextInt(5) + " left");
        };
        return switch (r.nextInt(3)) {
            case 0 -> new String[]{"ERROR", "de.example.shop.payment.PaymentService", "Payment for order " + order + " failed: card declined",
                "de.example.shop.payment.PaymentDeclinedException: card declined (code=05)\n"
                + "\tat de.example.shop.payment.PaymentClient.charge(PaymentClient.java:118)\n"
                + "\tat de.example.shop.payment.PaymentService.authorize(PaymentService.java:64)\n"
                + "\tat de.example.shop.order.OrderService.checkout(OrderService.java:92)\n"
                + "\tat de.example.shop.web.CheckoutController.checkout(CheckoutController.java:41)\n"};
            case 1 -> new String[]{"ERROR", "de.example.shop.shipping.ShippingService", "Could not create shipment for order " + order,
                "java.net.SocketTimeoutException: Read timed out\n"
                + "\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:278)\n"
                + "\tat java.base/java.net.Socket$SocketInputStream.read(Socket.java:1099)\n"
                + "\tat de.example.shop.shipping.CarrierClient.createShipment(CarrierClient.java:77)\n"
                + "\tat de.example.shop.shipping.ShippingService.ship(ShippingService.java:53)\n"};
            default -> new String[]{"ERROR", "org.springframework.web.servlet.DispatcherServlet",
                "Request processing failed: Cannot invoke \"String.trim()\" because \"coupon\" is null",
                "java.lang.NullPointerException: Cannot invoke \"String.trim()\" because \"coupon\" is null\n"
                + "\tat de.example.shop.cart.CouponService.apply(CouponService.java:29)\n"
                + "\tat de.example.shop.cart.CartController.applyCoupon(CartController.java:58)\n"};
        };
    }

    private static String[] ev(String level, String logger, String msg) {
        return new String[]{level, logger, msg, null};
    }

    static int levelValue(String l) {
        return switch (l) { case "TRACE" -> 5000; case "DEBUG" -> 10000; case "INFO" -> 20000; case "WARN" -> 30000; default -> 40000; };
    }

    private String hex(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        return sb.toString();
    }

    // ---------------------------------------------------------------- Kommandozeile

    public static void main(String[] a) throws IOException {
        if (a.length < 1) {
            System.out.println("Aufruf: java -cp beswinged.jar jsonlogviewer.LogGenerator ziel.jsonl [zeilen=240] [schema.yaml]");
            return;
        }
        int count = a.length > 1 ? Integer.parseInt(a[1]) : 240;
        Schema schema = a.length > 2 ? Schema.load(Path.of(a[2])) : null;
        Instant start = Instant.now().minusSeconds(15L * count).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        new LogGenerator(start, 15, 42).write(Path.of(a[0]), count, schema, null, null);
        System.out.println(count + " Zeilen geschrieben: " + a[0]);
    }
}
