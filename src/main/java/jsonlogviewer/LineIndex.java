package jsonlogviewer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Index einer (beliebig großen) JSON-Lines-Datei.
 *
 * <ul>
 *   <li>Zeilenanfänge als Byte-Offsets (8 Byte pro Zeile). Leere Zeilen werden übersprungen.</li>
 *   <li>Zeitstempel jeder {@value #TS_STEP}. Zeile (Epoch-Millis) für die Zeitsuche.</li>
 *   <li>Modus {@link Mode#RAM}: der Dateiinhalt wird beim Indizieren zusätzlich in den Speicher kopiert.
 *       Modus {@link Mode#SEEK}: Zeilen werden bei Bedarf per Positional-Read aus der Datei gelesen.</li>
 * </ul>
 *
 * Thread-Sicherheit: genau ein Thread schreibt ({@link #build}, danach {@link #extend}),
 * beliebige Threads dürfen gleichzeitig lesen. Sichtbar sind immer nur {@link #lineCount()} Zeilen.
 */
public final class LineIndex implements Closeable {

    public enum Mode { RAM, SEEK }

    /** Abstand der Zeilen im Zeitstempel-Index. */
    public static final int TS_STEP = 20;

    private static final int CHUNK_BITS = 16;
    private static final int CHUNK = 1 << CHUNK_BITS;
    private static final int MASK = CHUNK - 1;
    private static final int MAX_LINE_BYTES = 64 << 20;
    private static final int READ_BLOCK = 8 << 20;

    private final Path path;
    private final FileChannel channel;
    private final Mode mode;
    private final RamStore ram; // nur Modus RAM

    // Offsets: Start jeder nicht-leeren Zeile, am Ende zusätzlich das Dateiende als Sentinel.
    private volatile long[][] chunks = new long[64][];
    private volatile int size;
    private volatile boolean complete;
    private volatile long bytesScanned;
    private volatile long fileSize;

    // Scan-Zustand (nur Schreib-Thread)
    private long scannedPos;
    private boolean atLineStart = true;

    // Zeitstempel-Index: tsValues[i] = Zeitstempel von Zeile i*TS_STEP (fortgeschrieben, falls unbekannt)
    private volatile long[] tsValues = new long[1024];
    private volatile int tsCount;
    private volatile String[] tsPath; // null = beim ersten Treffer automatisch erkennen

    public LineIndex(Path path, Mode mode, String[] timestampPath) throws IOException {
        this.path = path;
        this.mode = mode;
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.fileSize = channel.size();
        this.ram = mode == Mode.RAM ? new RamStore() : null;
        this.tsPath = timestampPath;
    }

    public Path path() { return path; }
    public Mode mode() { return mode; }
    public boolean isComplete() { return complete; }
    public long bytesScanned() { return bytesScanned; }
    public long fileSize() { return fileSize; }

    /** Anzahl indizierter Zeilen. */
    public int lineCount() { return Math.max(0, size - 1); }

    /** Pfad des Zeitstempels (erkannt oder vorgegeben), null wenn noch keiner gefunden. */
    public String[] timestampPath() { return tsPath; }

    // ------------------------------------------------------------------ Offsets

    private void add(long v) {
        int s = size;
        if (s == Integer.MAX_VALUE) throw new IllegalStateException("Zu viele Zeilen");
        int c = s >>> CHUNK_BITS;
        long[][] ch = chunks;
        if (c >= ch.length) { ch = Arrays.copyOf(ch, ch.length * 2); chunks = ch; }
        if (ch[c] == null) ch[c] = new long[CHUNK];
        ch[c][s & MASK] = v;
        size = s + 1; // volatile write veröffentlicht den Wert
    }

    private long offset(int i) {
        return chunks[i >>> CHUNK_BITS][i & MASK];
    }

    // ------------------------------------------------------------------ Aufbau

    /** Baut den Index (blockierend, im Hintergrund-Thread aufrufen). */
    public void build(BooleanSupplier cancelled) throws IOException {
        long total = channel.size();
        fileSize = total;
        scan(total, cancelled);
        if (size > 0) add(scannedPos); // Sentinel = Dateiende
        complete = true;
    }

    /**
     * Liest neu angehängte Daten ein (Tail). Nur nach {@link #build} aufrufen.
     * @return Anzahl neuer Zeilen, oder -1 wenn die Datei kürzer geworden ist (neu öffnen)
     */
    public synchronized int extend() throws IOException {
        long total = channel.size();
        if (total < scannedPos) return -1;
        if (total == scannedPos) return 0;
        int before = lineCount();
        if (size > 0) size = size - 1; // Sentinel entfernen; letzte Zeile kann länger werden
        fileSize = total;
        scan(total, () -> false);
        if (size > 0) add(scannedPos);
        return lineCount() - before;
    }

    private void scan(long total, BooleanSupplier cancelled) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(READ_BLOCK);
        byte[] a = buf.array();
        long pos = scannedPos;
        while (pos < total) {
            if (cancelled.getAsBoolean()) return;
            buf.clear();
            if (total - pos < buf.capacity()) buf.limit((int) (total - pos));
            int n = channel.read(buf, pos);
            if (n <= 0) break;
            if (ram != null) ram.append(a, n); // vor dem Veröffentlichen der Zeilen
            for (int k = 0; k < n; k++) {
                byte b = a[k];
                if (b == '\n') atLineStart = true;
                else if (atLineStart && b != '\r') {
                    long start = pos + k;
                    sampleTimestampBefore(start, a, pos);
                    add(start);
                    atLineStart = false;
                }
            }
            pos += n;
            scannedPos = pos;
            bytesScanned = pos;
        }
        // letzte Zeile ist durch das Dateiende abgeschlossen
        sampleTimestampBefore(pos, a, Long.MIN_VALUE);
    }

    /**
     * Wird aufgerufen, bevor eine Zeile beginnt, die bei {@code end} startet (oder am Dateiende):
     * die vorherige Zeile ist dann vollständig. Ist sie eine jede TS_STEP-te, Zeitstempel merken.
     */
    private void sampleTimestampBefore(long end, byte[] buf, long bufStart) throws IOException {
        int prev = size - 1;
        if (prev < 0 || prev % TS_STEP != 0) return;
        long s = offset(prev);
        if (end <= s) return;
        String text;
        int len = (int) Math.min(end - s, MAX_LINE_BYTES);
        if (bufStart != Long.MIN_VALUE && s >= bufStart) {
            int off = (int) (s - bufStart);
            text = new String(buf, off, trimEnd(buf, off, len), StandardCharsets.UTF_8);
        } else {
            text = readRange(s, len);
        }
        setTimestamp(prev / TS_STEP, timestampMillis(text));
    }

    private void setTimestamp(int slot, long value) {
        long[] arr = tsValues;
        if (value == Long.MIN_VALUE && slot > 0) value = arr[slot - 1]; // unbekannt: fortschreiben
        if (slot >= arr.length) {
            arr = Arrays.copyOf(arr, arr.length * 2);
            arr[slot] = value;
            tsValues = arr;
        } else arr[slot] = value;
        if (slot >= tsCount) tsCount = slot + 1;
    }

    // ------------------------------------------------------------------ Zeitstempel

    /** Zeitstempel einer Zeile (Epoch-Millis) oder {@link Long#MIN_VALUE}. */
    public long timestampMillis(String line) {
        String raw = timestampText(line);
        Instant i = raw == null ? null : CellFormatter.parseInstant(raw);
        return i == null ? Long.MIN_VALUE : i.toEpochMilli();
    }

    /** Zeitstempel-Text einer Zeile wie in der Datei, oder null. Erkennt den Pfad beim ersten Treffer. */
    public String timestampText(String line) {
        Object root;
        try {
            root = Json.parse(line);
        } catch (RuntimeException e) {
            return null;
        }
        String[] p = tsPath;
        if (p != null) {
            Object v = Json.get(root, p);
            return v instanceof String s ? s : null;
        }
        if (!(root instanceof Map<?, ?> m)) return null;
        String fallbackKey = null;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getValue() instanceof String s) || CellFormatter.parseInstant(s) == null) continue;
            String key = String.valueOf(e.getKey());
            String lk = key.toLowerCase(Locale.ROOT);
            if (lk.contains("time") || lk.equals("@t") || lk.contains("date") || lk.equals("ts")) {
                tsPath = new String[]{key};
                return s;
            }
            if (fallbackKey == null) fallbackKey = key;
        }
        if (fallbackKey != null) {
            tsPath = new String[]{fallbackKey};
            return (String) m.get(fallbackKey);
        }
        return null;
    }

    /** Anzahl Einträge im Zeitstempel-Index. */
    public int timestampSlots() { return tsCount; }

    /**
     * Sucht die erste Zeile, deren Zeitstempel {@code >= target} ist. Über den Index wird der Block
     * gefunden, nur dessen Zeilen werden gelesen und geparst.
     * @return Zeilennummer, oder -1 wenn kein Index vorhanden
     */
    public int findLineAtOrAfter(long target) throws IOException {
        int n = tsCount;
        int lines = lineCount();
        if (n == 0 || lines == 0) return -1;
        long[] arr = tsValues;
        int lo = 0, hi = n - 1, block = 0; // letzter Block mit ts <= target
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] != Long.MIN_VALUE && arr[mid] <= target) { block = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        int from = block * TS_STEP;
        int to = Math.min(lines, from + 2 * TS_STEP);
        for (int i = from; i < to; i++) {
            long t = timestampMillis(readLine(i));
            if (t != Long.MIN_VALUE && t >= target) return i;
        }
        return to; // == lines: alle Zeitstempel liegen davor
    }

    // ------------------------------------------------------------------ Lesen

    /** Liest Zeile {@code i} (0-basiert, nur Nicht-Leerzeilen). */
    public String readLine(int i) throws IOException {
        long start = offset(i);
        long end = offset(i + 1);
        return readRange(start, (int) Math.min(end - start, MAX_LINE_BYTES));
    }

    private String readRange(long start, int len) throws IOException {
        byte[] a = new byte[len];
        int got = read(start, a, 0, len);
        return new String(a, 0, trimEnd(a, 0, got), StandardCharsets.UTF_8);
    }

    /** Liest Bytes aus RAM oder Datei. @return Anzahl gelesener Bytes */
    private int read(long pos, byte[] dst, int off, int len) throws IOException {
        if (ram != null) return ram.read(pos, dst, off, len);
        ByteBuffer b = ByteBuffer.wrap(dst, off, len);
        long p = pos;
        while (b.hasRemaining()) {
            int n = channel.read(b, p);
            if (n < 0) break;
            p += n;
        }
        return b.position() - off;
    }

    private static int trimEnd(byte[] a, int off, int len) {
        while (len > 0) {
            byte c = a[off + len - 1];
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') len--; else break;
        }
        return len;
    }

    public interface LineVisitor {
        /** @return false um abzubrechen */
        boolean visit(int line, String text);
    }

    /** Liest die Zeilen [from, to) sequenziell mit großen Blöcken (schnell für Volltextsuche). */
    public void scan(int from, int to, LineVisitor visitor) throws IOException {
        final int block = 16 << 20;
        byte[] a = new byte[block];
        long bufStart = 0;
        int bufLen = 0;
        for (int i = from; i < to; i++) {
            long s = offset(i);
            long e = offset(i + 1);
            String text;
            if (e - s > block) {
                text = readLine(i);
            } else {
                if (s < bufStart || e > bufStart + bufLen) {
                    bufStart = s;
                    bufLen = read(s, a, 0, block);
                }
                int off = (int) (s - bufStart);
                int len = trimEnd(a, off, (int) Math.min(e - s, bufLen - off));
                text = new String(a, off, len, StandardCharsets.UTF_8);
            }
            if (!visitor.visit(i, text)) return;
        }
    }

    /** Belegter Speicher für den Dateiinhalt (nur Modus RAM). */
    public long ramBytes() { return ram == null ? 0 : ram.length(); }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // ------------------------------------------------------------------ RAM-Speicher

    /** Dateiinhalt in Blöcken zu 64 MB im Heap. Ein Schreiber, beliebig viele Leser. */
    private static final class RamStore {
        private static final int BITS = 26;
        private static final int BLOCK = 1 << BITS;
        private volatile byte[][] blocks = new byte[16][];
        private volatile long length;

        void append(byte[] src, int n) {
            long len = length;
            int off = 0;
            while (off < n) {
                int b = (int) (len >>> BITS);
                int inBlock = (int) (len & (BLOCK - 1));
                byte[][] bl = blocks;
                if (b >= bl.length) { bl = Arrays.copyOf(bl, bl.length * 2); blocks = bl; }
                if (bl[b] == null) bl[b] = new byte[BLOCK];
                int c = Math.min(n - off, BLOCK - inBlock);
                System.arraycopy(src, off, bl[b], inBlock, c);
                off += c;
                len += c;
            }
            length = len;
        }

        int read(long pos, byte[] dst, int off, int len) {
            long avail = length - pos;
            if (avail <= 0) return 0;
            int total = (int) Math.min(len, avail);
            byte[][] bl = blocks;
            int done = 0;
            while (done < total) {
                long p = pos + done;
                int b = (int) (p >>> BITS);
                int inBlock = (int) (p & (BLOCK - 1));
                int c = Math.min(total - done, BLOCK - inBlock);
                System.arraycopy(bl[b], inBlock, dst, off + done, c);
                done += c;
            }
            return total;
        }

        long length() { return length; }
    }
}
