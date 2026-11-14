package ro.filegrid.classification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import ro.filegrid.classification.ClassificationResult.Basis;
import ro.filegrid.classification.ClassificationResult.CategoryMatch;
import ro.filegrid.classification.ClassificationResult.Warning;
import ro.filegrid.file.FileCategory;
import ro.filegrid.policy.Policy;
import ro.filegrid.policy.Policy.Detection;
import ro.filegrid.policy.PolicyService;
import ro.filegrid.text.Texts;

/**
 * Establishes the category of a file from: what the uploader declared, the
 * MIME type read from the first bytes (magic bytes, Apache Tika), the file
 * extension and the context fields. The rules come from the active policy.
 *
 * <p>A declared category always wins; when the content points elsewhere, a
 * warning is recorded and nothing is blocked. Deterministic: the same input
 * and policy always give the same result.
 */
@Service
public class ClassificationService {

    public static final String WARNING_MISMATCH = "DECLARED_DIFFERS_FROM_DETECTED";
    public static final String WARNING_AMBIGUOUS = "AMBIGUOUS_DETECTION";
    public static final String WARNING_MISSING_FIELDS = "MISSING_REQUIRED_FIELDS";
    public static final String WARNING_UNCLASSIFIED = "UNCLASSIFIED";

    private static final int MIME_POINTS = 2;
    private static final int EXTENSION_POINTS = 1;
    private static final int FIELDS_POINTS = 2;
    private static final String OCTET_STREAM = "application/octet-stream";

    private final PolicyService policies;
    private final Texts texts;
    private final Tika tika = new Tika();

    public ClassificationService(PolicyService policies, Texts texts) {
        this.policies = policies;
        this.texts = texts;
    }

    public ClassificationResult classify(ClassificationRequest request) {
        return classify(request, policies.active().policy());
    }

    ClassificationResult classify(ClassificationRequest request, Policy policy) {
        String extension = extensionOf(request.fileName());
        String mimeType = detectMimeType(request.head(), request.fileName());

        List<CategoryMatch> matches = new ArrayList<>();
        for (FileCategory category : FileCategory.values()) {
            CategoryMatch match = match(category, policy.category(category).detection(), mimeType, extension,
                    request.metadata());
            if (match != null) {
                matches.add(match);
            }
        }
        // Best score first. On a tie the category that requires encryption comes first:
        // a document with personal data must never be stored unprotected by mistake.
        matches.sort(Comparator.comparingInt(CategoryMatch::score).reversed()
                .thenComparing(match -> !policy.category(match.category()).constraints().needsEncryption())
                .thenComparing(CategoryMatch::category));

        FileCategory detected = matches.isEmpty() ? null : matches.get(0).category();
        FileCategory declared = request.declaredCategory();
        FileCategory chosen = declared != null ? declared : detected;
        Basis basis = declared != null ? Basis.DECLARED : detected != null ? Basis.DETECTED : Basis.NONE;

        List<Warning> warnings = new ArrayList<>();
        if (declared != null && detected != null && declared != detected
                && !isTopMatch(declared, matches)) {
            warnings.add(warning(WARNING_MISMATCH, name(declared), name(detected), mimeType));
        } else if (declared != null && detected == null) {
            warnings.add(warningWithKey(WARNING_MISMATCH + ".unknown", WARNING_MISMATCH, name(declared), mimeType));
        }
        if (declared == null && matches.size() > 1 && matches.get(0).score() == matches.get(1).score()) {
            warnings.add(warning(WARNING_AMBIGUOUS, name(matches.get(0).category()),
                    name(matches.get(1).category())));
        }
        if (chosen == null) {
            warnings.add(warning(WARNING_UNCLASSIFIED, mimeType, extension == null ? "-" : extension));
        } else {
            List<String> missing = missingFields(policy.category(chosen).detection(), request.metadata());
            if (!missing.isEmpty()) {
                warnings.add(warning(WARNING_MISSING_FIELDS, name(chosen), String.join(", ", missing)));
            }
        }
        return new ClassificationResult(chosen, basis, declared, detected, mimeType,
                request.declaredContentType(), extension, List.copyOf(matches), List.copyOf(warnings));
    }

    /** Magic bytes first; Tika refines only compatible sub-types from the file name. */
    private String detectMimeType(byte[] head, String fileName) {
        if (head.length == 0) {
            return fileName == null || fileName.isBlank() ? OCTET_STREAM : tika.detect(fileName);
        }
        return fileName == null || fileName.isBlank() ? tika.detect(head) : tika.detect(head, fileName);
    }

    private static boolean isTopMatch(FileCategory category, List<CategoryMatch> matches) {
        if (matches.isEmpty()) {
            return false;
        }
        int bestScore = matches.get(0).score();
        return matches.stream().anyMatch(
                match -> match.category() == category && match.score() == bestScore && match.mimeTypeMatches());
    }

    private CategoryMatch match(FileCategory category, Detection detection, String mimeType, String extension,
                                Map<String, String> metadata) {
        boolean mimeMatches = detection.mimeTypes() != null
                && detection.mimeTypes().stream().anyMatch(pattern -> mimeMatches(pattern, mimeType));
        boolean extensionMatches = extension != null && detection.extensions() != null
                && detection.extensions().contains(extension);
        if (!mimeMatches && !extensionMatches) {
            return null;
        }
        List<String> missing = missingFields(detection, metadata);
        boolean fieldsConfirm = !detection.requiredFields().isEmpty() && missing.isEmpty();
        int score = (mimeMatches ? MIME_POINTS : 0) + (extensionMatches ? EXTENSION_POINTS : 0)
                + (fieldsConfirm ? FIELDS_POINTS : 0);
        return new CategoryMatch(category, mimeMatches, extensionMatches, missing, score);
    }

    private static boolean mimeMatches(String pattern, String mimeType) {
        if (pattern.endsWith("/*")) {
            return mimeType.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(mimeType);
    }

    private static List<String> missingFields(Detection detection, Map<String, String> metadata) {
        return detection.requiredFields().stream()
                .filter(field -> {
                    String value = metadata.get(field);
                    return value == null || value.isBlank();
                })
                .toList();
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String name(FileCategory category) {
        return texts.get("category." + category.name());
    }

    private Warning warning(String code, Object... args) {
        return new Warning(code, texts.get("classification.warning." + code, args));
    }

    /** Variant whose text key differs from the public code. */
    private Warning warningWithKey(String textKey, String code, String... args) {
        return new Warning(code, texts.get("classification.warning." + textKey, (Object[]) args));
    }
}
