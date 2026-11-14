package ro.filegrid.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Random;
import ro.filegrid.storage.Sha256;
import ro.filegrid.storage.StreamSource;

/**
 * Test content of any size that is generated while it is read, never held in
 * memory. The same seed and size always produce the same bytes, so a test can
 * compute the expected SHA-256 independently of the code under test.
 */
public final class DeterministicContent implements StreamSource {

    private static final int BLOCK = 64 * 1024;

    private final long seed;
    private final long size;

    public DeterministicContent(long seed, long size) {
        this.seed = seed;
        this.size = size;
    }

    public long size() {
        return size;
    }

    @Override
    public InputStream open() {
        return new GeneratedStream(seed, size);
    }

    /** SHA-256 of the whole content. */
    public String sha256() {
        return sha256Of(open());
    }

    /** Streams the input to the end and returns its SHA-256 (the stream is closed). */
    public static String sha256Of(InputStream in) {
        MessageDigest digest = Sha256.newDigest();
        byte[] buffer = new byte[BLOCK];
        try (in) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Sha256.hex(digest);
    }

    private static final class GeneratedStream extends InputStream {

        private final Random random;
        private final byte[] block = new byte[BLOCK];
        private long remaining;
        private int blockOffset = BLOCK;

        GeneratedStream(long seed, long size) {
            this.random = new Random(seed);
            this.remaining = size;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            refillIfNeeded();
            remaining--;
            return block[blockOffset++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            if (remaining <= 0) {
                return -1;
            }
            refillIfNeeded();
            int count = (int) Math.min(Math.min(length, BLOCK - blockOffset), remaining);
            System.arraycopy(block, blockOffset, buffer, offset, count);
            blockOffset += count;
            remaining -= count;
            return count;
        }

        // Blocks are always generated whole, so the bytes do not depend on how the caller reads.
        private void refillIfNeeded() {
            if (blockOffset == BLOCK) {
                random.nextBytes(block);
                blockOffset = 0;
            }
        }
    }
}
