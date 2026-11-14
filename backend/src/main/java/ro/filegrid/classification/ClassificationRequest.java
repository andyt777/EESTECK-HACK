package ro.filegrid.classification;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import ro.filegrid.file.FileCategory;

/**
 * What is known about a file when it has to be classified.
 *
 * @param declaredCategory    category chosen by the uploader (the "house"), or {@code null}
 * @param fileName            original file name
 * @param declaredContentType content type sent by the client, or {@code null}; recorded, never trusted
 * @param head                the first bytes of the content (at most {@link #HEAD_BYTES}), never the whole file
 * @param metadata            operational context filled in at upload: zone, station, equipment, meterId,
 *                            eventType, eventTime, description
 */
public record ClassificationRequest(FileCategory declaredCategory, String fileName, String declaredContentType,
                                    byte[] head, Map<String, String> metadata) {

    /** How much of the content is looked at: enough for magic bytes, independent of the file size. */
    public static final int HEAD_BYTES = 64 * 1024;

    public ClassificationRequest {
        head = head == null ? new byte[0] : head;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (head.length > HEAD_BYTES) {
            throw new IllegalArgumentException("Only the first " + HEAD_BYTES + " bytes may be passed");
        }
    }

    /** Reads at most {@link #HEAD_BYTES} from the stream (which stays open). */
    public static byte[] readHead(InputStream content) throws IOException {
        return content.readNBytes(HEAD_BYTES);
    }
}
