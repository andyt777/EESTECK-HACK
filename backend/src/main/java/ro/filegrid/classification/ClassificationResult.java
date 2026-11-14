package ro.filegrid.classification;

import java.util.List;
import ro.filegrid.file.FileCategory;

/**
 * Outcome of the classification. It is saved as {@code routing_decision.detection},
 * so the Decision Explorer shows how the category was really established.
 *
 * @param category            the category used from here on; {@code null} when the file could not be classified
 * @param basis               where {@code category} comes from
 * @param declaredCategory    what the uploader chose, or {@code null}
 * @param detectedCategory    what the content, extension and context point to, or {@code null}
 * @param detectedMimeType    MIME type read from the content (magic bytes), refined by the file name
 * @param declaredContentType content type sent by the client
 * @param extension           lower-case extension without the dot, or {@code null}
 * @param matches             every category whose detection rules matched, best first
 * @param warnings            things the user should know; they never block the upload
 */
public record ClassificationResult(FileCategory category, Basis basis, FileCategory declaredCategory,
                                   FileCategory detectedCategory, String detectedMimeType,
                                   String declaredContentType, String extension,
                                   List<CategoryMatch> matches, List<Warning> warnings) {

    public enum Basis {
        /** The uploader's choice was used. */
        DECLARED,
        /** No category was declared; the detected one was used. */
        DETECTED,
        /** Nothing was declared and nothing matched. */
        NONE
    }

    /**
     * How well one category's detection rules fit the file.
     *
     * @param missingFields required context fields that were not filled in
     */
    public record CategoryMatch(FileCategory category, boolean mimeTypeMatches, boolean extensionMatches,
                                List<String> missingFields, int score) {
    }

    /**
     * @param code stable identifier (for the UI and for tests)
     * @param text Romanian text shown to the user
     */
    public record Warning(String code, String text) {
    }

    public boolean hasWarning(String code) {
        return warnings.stream().anyMatch(warning -> warning.code().equals(code));
    }
}
