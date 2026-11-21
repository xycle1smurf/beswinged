package jsonlogviewer.format;

/**
 * Empfohlene Feldnamen. Sie entsprechen denen von logstash-logback-encoder, damit Schemas für JSON-Logs
 * (z.B. das mitgelieferte Logstash-Schema) auch für Text-Logs passen.
 */
public final class Fields {
    public static final String TIMESTAMP = "@timestamp";
    public static final String LEVEL = "level";
    public static final String THREAD = "thread_name";
    public static final String THREAD_ID = "thread_id";
    public static final String LOGGER = "logger_name";
    public static final String MESSAGE = "message";
    public static final String STACK_TRACE = "stack_trace";
    public static final String CLASS = "caller_class_name";
    public static final String METHOD = "caller_method_name";
    public static final String FILE = "caller_file_name";
    public static final String LINE = "caller_line_number";
    public static final String PID = "pid";

    private Fields() { }
}
