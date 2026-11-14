package ro.filegrid.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Content that can be opened more than once (for example a staging file).
 * Needed by transfers that may be resumed: after a failure the storage adapter
 * reopens the source and continues from the bytes already stored.
 */
@FunctionalInterface
public interface StreamSource {

    /** Opens a new stream positioned at the first byte. The caller closes it. */
    InputStream open() throws IOException;
}
