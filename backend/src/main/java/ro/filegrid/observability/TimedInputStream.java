package ro.filegrid.observability;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps a stream read from a storage and reports, once, on close: how long the
 * read took, how many bytes went through and whether a read failed. Nothing is
 * buffered: bytes only pass through.
 */
public final class TimedInputStream extends FilterInputStream {

    /** Receives the result when the stream is closed. */
    @FunctionalInterface
    public interface Listener {
        void completed(String outcome, long nanos, long bytes);
    }

    private final Listener listener;
    private final long started = System.nanoTime();
    private long bytes;
    private boolean failed;
    private boolean reported;

    public TimedInputStream(InputStream in, Listener listener) {
        super(in);
        this.listener = listener;
    }

    @Override
    public int read() throws IOException {
        try {
            int b = super.read();
            if (b >= 0) {
                bytes++;
            }
            return b;
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        try {
            int n = super.read(buffer, off, len);
            if (n > 0) {
                bytes += n;
            }
            return n;
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            if (!reported) {
                reported = true;
                listener.completed(failed ? FileGridMetrics.ERROR : FileGridMetrics.SUCCESS,
                        System.nanoTime() - started, bytes);
            }
        }
    }
}
