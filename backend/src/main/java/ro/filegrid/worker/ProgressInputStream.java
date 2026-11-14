package ro.filegrid.worker;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongConsumer;

/**
 * Counts the bytes read and reports progress every {@code step} bytes (and
 * once at the end). Reporting happens on the reading thread, between reads.
 */
final class ProgressInputStream extends FilterInputStream {

    /** Progress is reported at every ~5% of the file, but at least every 16 MB. */
    static long stepFor(long size) {
        long fivePercent = size / 20;
        return Math.max(64 * 1024L, Math.min(fivePercent, 16L * 1024 * 1024));
    }

    private final long step;
    private final LongConsumer listener;
    private long count;
    private long nextReport;

    ProgressInputStream(InputStream in, long size, LongConsumer listener) {
        super(in);
        this.step = stepFor(size);
        this.listener = listener;
        this.nextReport = step;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            advance(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            advance(read);
        }
        return read;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        if (skipped > 0) {
            advance(skipped);
        }
        return skipped;
    }

    private void advance(long bytes) {
        count += bytes;
        if (count >= nextReport) {
            listener.accept(count);
            nextReport = (count / step + 1) * step;
        }
    }

    long count() {
        return count;
    }
}
