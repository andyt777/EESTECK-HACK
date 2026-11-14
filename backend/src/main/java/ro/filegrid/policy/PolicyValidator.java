package ro.filegrid.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ro.filegrid.file.FileCategory;
import ro.filegrid.policy.Policy.CategoryPolicy;
import ro.filegrid.policy.Policy.Constraints;
import ro.filegrid.policy.Policy.Detection;
import ro.filegrid.policy.Policy.Retry;
import ro.filegrid.policy.Policy.Sla;
import ro.filegrid.policy.Policy.StorageCapabilities;
import ro.filegrid.policy.Policy.Weights;
import ro.filegrid.storage.StorageType;
import ro.filegrid.text.Texts;

/**
 * Checks that a parsed policy is complete and consistent. It reports every
 * problem it finds, not only the first one, so the administrator can fix the
 * file in one pass. Messages are in Romanian (they are shown in the admin UI).
 */
public final class PolicyValidator {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAX_ATTEMPTS_LIMIT = 20;

    private final Texts texts;

    public PolicyValidator(Texts texts) {
        this.texts = texts;
    }

    /** @return the problems found; empty when the policy can be used */
    public List<String> validate(Policy policy) {
        Errors errors = new Errors();
        if (policy.schemaVersion() == null || policy.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            errors.add("policy.error.schemaVersion", SUPPORTED_SCHEMA_VERSION);
        }
        validateStorages(policy.storages(), errors);
        validateCategories(policy.categories(), errors);
        return errors.list;
    }

    private void validateStorages(Map<StorageType, StorageCapabilities> storages, Errors errors) {
        for (StorageType storage : StorageType.values()) {
            String path = "storages." + storage;
            StorageCapabilities capabilities = storages == null ? null : storages.get(storage);
            if (capabilities == null) {
                errors.add("policy.error.missing", path);
                continue;
            }
            if (capabilities.maxSizeBytes() != null && capabilities.maxSizeBytes() <= 0) {
                errors.add("policy.error.positive", path + ".maxSizeBytes");
            }
            errors.required(capabilities.resumable(), path + ".resumable");
            errors.required(capabilities.immediateRead(), path + ".immediateRead");
            errors.required(capabilities.idempotentWrites(), path + ".idempotentWrites");
        }
    }

    private void validateCategories(Map<FileCategory, CategoryPolicy> categories, Errors errors) {
        for (FileCategory category : FileCategory.values()) {
            String path = "categories." + category;
            CategoryPolicy rules = categories == null ? null : categories.get(category);
            if (rules == null) {
                errors.add("policy.error.missing", path);
                continue;
            }
            validateDetection(rules.detection(), path + ".detection", errors);
            validateDestinations(rules, path, errors);
            validateConstraints(rules.constraints(), path + ".constraints", errors);
            validateWeights(rules.weights(), path + ".weights", errors);
            validateRetry(rules.retry(), path + ".retry", errors);
            validateSla(rules.sla(), path + ".sla", errors);
        }
    }

    private void validateDetection(Detection detection, String path, Errors errors) {
        if (detection == null) {
            errors.add("policy.error.missing", path);
            return;
        }
        boolean hasExtensions = detection.extensions() != null && !detection.extensions().isEmpty();
        boolean hasMimeTypes = detection.mimeTypes() != null && !detection.mimeTypes().isEmpty();
        if (!hasExtensions && !hasMimeTypes) {
            errors.add("policy.error.detectionEmpty", path);
        }
        if (hasExtensions) {
            for (String extension : detection.extensions()) {
                if (extension == null || !extension.matches("[a-z0-9]{1,16}")) {
                    errors.add("policy.error.extension", path + ".extensions", String.valueOf(extension));
                }
            }
        }
        if (hasMimeTypes) {
            for (String mimeType : detection.mimeTypes()) {
                if (mimeType == null || !mimeType.matches("[a-z0-9.+-]+/([a-z0-9.+-]+|\\*)")) {
                    errors.add("policy.error.mimeType", path + ".mimeTypes", String.valueOf(mimeType));
                }
            }
        }
        if (detection.requiredFields() == null) {
            errors.add("policy.error.missing", path + ".requiredFields");
        }
    }

    private void validateDestinations(CategoryPolicy rules, String path, Errors errors) {
        errors.required(rules.primary(), path + ".primary");
        if (rules.fallbacks() == null) {
            errors.add("policy.error.missing", path + ".fallbacks");
            return;
        }
        Set<StorageType> seen = new HashSet<>();
        for (StorageType fallback : rules.fallbacks()) {
            if (fallback == null) {
                errors.add("policy.error.missing", path + ".fallbacks[]");
            } else if (fallback == rules.primary()) {
                errors.add("policy.error.fallbackIsPrimary", path + ".fallbacks", fallback);
            } else if (!seen.add(fallback)) {
                errors.add("policy.error.fallbackDuplicate", path + ".fallbacks", fallback);
            }
        }
    }

    private void validateConstraints(Constraints constraints, String path, Errors errors) {
        if (constraints == null) {
            errors.add("policy.error.missing", path);
            return;
        }
        errors.required(constraints.needsResumable(), path + ".needsResumable");
        errors.required(constraints.needsEncryption(), path + ".needsEncryption");
        errors.required(constraints.needsImmediateRead(), path + ".needsImmediateRead");
        errors.required(constraints.idempotent(), path + ".idempotent");
        if (constraints.maxSizeBytes() != null) {
            constraints.maxSizeBytes().forEach((storage, limit) -> {
                if (limit == null || limit <= 0) {
                    errors.add("policy.error.positive", path + ".maxSizeBytes." + storage);
                }
            });
        }
    }

    private void validateWeights(Weights weights, String path, Errors errors) {
        if (weights == null) {
            errors.add("policy.error.missing", path);
            return;
        }
        boolean complete = true;
        complete &= weight(weights.sizeFit(), path + ".sizeFit", errors);
        complete &= weight(weights.resumable(), path + ".resumable", errors);
        complete &= weight(weights.availability(), path + ".availability", errors);
        complete &= weight(weights.measuredLatencyP95(), path + ".measuredLatencyP95", errors);
        complete &= weight(weights.policyPreference(), path + ".policyPreference", errors);
        if (complete && weights.total() <= 0) {
            errors.add("policy.error.weightsZero", path);
        }
    }

    private boolean weight(Double value, String path, Errors errors) {
        if (value == null) {
            errors.add("policy.error.missing", path);
            return false;
        }
        if (value < 0 || value.isNaN() || value.isInfinite()) {
            errors.add("policy.error.weightNegative", path);
            return false;
        }
        return true;
    }

    private void validateRetry(Retry retry, String path, Errors errors) {
        if (retry == null) {
            errors.add("policy.error.missing", path);
            return;
        }
        if (retry.maxAttempts() == null || retry.maxAttempts() < 1 || retry.maxAttempts() > MAX_ATTEMPTS_LIMIT) {
            errors.add("policy.error.maxAttempts", path + ".maxAttempts", MAX_ATTEMPTS_LIMIT);
        }
        boolean hasInitial = retry.initialBackoff() != null;
        boolean hasMax = retry.maxBackoff() != null;
        if (!hasInitial) {
            errors.add("policy.error.missing", path + ".initialBackoff");
        } else if (retry.initialBackoff().isZero() || retry.initialBackoff().isNegative()) {
            errors.add("policy.error.positive", path + ".initialBackoff");
        }
        if (!hasMax) {
            errors.add("policy.error.missing", path + ".maxBackoff");
        } else if (hasInitial && retry.maxBackoff().compareTo(retry.initialBackoff()) < 0) {
            errors.add("policy.error.backoffOrder", path);
        }
    }

    private void validateSla(Sla sla, String path, Errors errors) {
        if (sla == null) {
            errors.add("policy.error.missing", path);
            return;
        }
        if (sla.maxPendingSeconds() == null || sla.maxPendingSeconds() <= 0) {
            errors.add("policy.error.positive", path + ".maxPendingSeconds");
        }
        if (sla.maxStoreP95Seconds() == null || sla.maxStoreP95Seconds() <= 0) {
            errors.add("policy.error.positive", path + ".maxStoreP95Seconds");
        }
    }

    private final class Errors {
        private final List<String> list = new ArrayList<>();

        void add(String code, Object... args) {
            list.add(texts.get(code, args));
        }

        void required(Object value, String path) {
            if (value == null) {
                add("policy.error.missing", path);
            }
        }
    }
}
