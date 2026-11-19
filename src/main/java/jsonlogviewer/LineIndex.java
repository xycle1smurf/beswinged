package jsonlogviewer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Index der Zeilenanfänge einer (beliebig großen) Datei. Es werden nur Byte-Offsets
 * gehalten (8 Byte pro Zeile), der Inhalt wird bei Bedarf per Positional-Read gelesen.
 * Leere Zeilen werden übersprungen.
 *
 * Thread-Sicherheit: genau ein Thread baut den Index ({@link #build}), beliebige Threads
 * dürfen gleichzeitig lesen. Sichtbar ist immer nur {@link #lineCount()} vollständige Zeilen.
 */
public final class LineIndex implements Closeable {

    private static final int CHUNK_BITS = 16;
    private static final int CHUNK = 1 << CHUNK_BITS;
    private static final int MASK = CHUNK - 1;
    private static final int MAX_LINE_BYTES = 64 << 20;

    private final Path path;
    private final FileChannel channel;

    // Offsets: Start jeder nicht-leeren Zeile, am Ende zusätzlich das Dateiende als Sentinel.
    private volatile long[][] chunks = new long[64][];
    private volatile int size;
    private volatile boolean complete;
    private volatile long bytesScanned;
    private volatile long fileSize;

    public LineIndex(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.fileSize = channel.size();
    }

    public Path path() { return path; }
    public boolean isComplete() { return complete; }
    public long bytesScanned() { return bytesScanned; }
    public long fileSize() { return fileSize; }

    /** Anzahl vollständig indizierter Zeilen. */
    public int lineCount() { return Math.max(0, size - 1); }

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

    /** Baut den Index (blockierend, im Hintergrund-Thread aufrufen). */
    public void build(BooleanSupplier cancelled) throws IOException {
        long total = channel.size();
        fileSize = total;
        ByteBuffer buf = ByteBuffer.allocate(8 << 20);
        byte[] a = buf.array();
        long pos = 0;
        boolean atStart = true;
        while (pos < total) {
            if (cancelled.getAsBoolean()) return;
            buf.clear();
            int n = channel.read(buf, pos);
            if (n <= 0) break;
            for (int k = 0; k < n; k++) {
                byte b = a[k];
                if (b == '\n') atStart = true;
                else if (atStart && b != '\r') {
                    add(pos + k);
                    atStart = false;
                }
            }
            pos += n;
            bytesScanned = pos;
        }
        if (size > 0) add(pos); // Sentinel = Dateiende
        complete = true;
    }

    /** Liest Zeile {@code i} (0-basiert, nur Nicht-Leerzeilen). */
    public String readLine(int i) throws IOException {
        long start = offset(i);
        long end = offset(i + 1);
        int len = (int) Math.min(end - start, MAX_LINE_BYTES);
        ByteBuffer b = ByteBuffer.allocate(len);
        long p = start;
        while (b.hasRemaining()) {
            int n = channel.read(b, p);
            if (n < 0) break;
            p += n;
        }
        byte[] a = b.array();
        int l = trimEnd(a, 0, b.position());
        return new String(a, 0, l, StandardCharsets.UTF_8);
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

    /**
     * Liest die Zeilen [from, to) sequenziell mit großen Blöcken (schnell für Volltextsuche).
     */
    public void scan(int from, int to, LineVisitor visitor) throws IOException {
        final int block = 16 << 20;
        ByteBuffer buf = ByteBuffer.allocate(block);
        byte[] a = buf.array();
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
                    buf.clear();
                    bufStart = s;
                    long p = s;
                    while (buf.hasRemaining()) {
                        int n = channel.read(buf, p);
                        if (n < 0) break;
                        p += n;
                    }
                    bufLen = buf.position();
                }
                int off = (int) (s - bufStart);
                int len = trimEnd(a, off, (int) Math.min(e - s, bufLen - off));
                text = new String(a, off, len, StandardCharsets.UTF_8);
            }
            if (!visitor.visit(i, text)) return;
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
