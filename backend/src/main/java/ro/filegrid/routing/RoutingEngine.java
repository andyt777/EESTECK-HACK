package ro.filegrid.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ro.filegrid.file.FileCategory;
import ro.filegrid.policy.Policy;
import ro.filegrid.policy.Policy.CategoryPolicy;
import ro.filegrid.policy.Policy.Constraints;
import ro.filegrid.policy.Policy.StorageCapabilities;
import ro.filegrid.policy.Policy.Weights;
import ro.filegrid.routing.RoutingOutcome.Candidate;
import ro.filegrid.routing.RoutingOutcome.Factor;
import ro.filegrid.routing.RoutingOutcome.Kind;
import ro.filegrid.routing.RoutingOutcome.Reason;
import ro.filegrid.routing.RoutingOutcome.Role;
import ro.filegrid.storage.StorageType;
import ro.filegrid.text.Sizes;
import ro.filegrid.text.Texts;

/**
 * The decision logic of Agent 01. Deterministic and free of I/O: the same
 * policy, file and storage states always give the same outcome.
 *
 * <p>For every storage it establishes whether the file can be stored there
 * (hard requirements), whether the storage is available, and a weighted score
 * from the values actually used. The primary is chosen when it is eligible and
 * available; otherwise the best-scoring fallback; otherwise nothing (the file
 * waits). The explanation is assembled from the very same reasons.
 */
@Component
public class RoutingEngine {

    public static final String FACTOR_SIZE_FIT = "sizeFit";
    public static final String FACTOR_RESUMABLE = "resumable";
    public static final String FACTOR_AVAILABILITY = "availability";
    public static final String FACTOR_LATENCY = "measuredLatencyP95";
    public static final String FACTOR_PREFERENCE = "policyPreference";

    public static final String PRIMARY = "ROLE_PRIMARY";
    public static final String FALLBACK = "ROLE_FALLBACK";
    public static final String NOT_IN_POLICY = "NOT_IN_POLICY";
    public static final String SIZE_OK = "SIZE_WITHIN_LIMIT";
    public static final String SIZE_UNLIMITED = "SIZE_UNLIMITED";
    public static final String SIZE_EXCEEDED = "SIZE_EXCEEDS_LIMIT";
    public static final String RESUMABLE_OK = "RESUMABLE_SUPPORTED";
    public static final String RESUMABLE_MISSING = "RESUMABLE_NOT_SUPPORTED";
    public static final String IMMEDIATE_READ_OK = "IMMEDIATE_READ_SUPPORTED";
    public static final String IMMEDIATE_READ_MISSING = "IMMEDIATE_READ_NOT_SUPPORTED";
    public static final String IDEMPOTENT_OK = "IDEMPOTENT_WRITES_SUPPORTED";
    public static final String IDEMPOTENT_MISSING = "IDEMPOTENT_WRITES_NOT_SUPPORTED";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String DOWN = "UNAVAILABLE";
    public static final String STATE_UNKNOWN = "STATE_UNKNOWN";
    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String LATENCY_MEASURED = "LATENCY_MEASURED";
    public static final String LATENCY_UNKNOWN = "LATENCY_UNKNOWN";

    /** Used for the latency criterion when nothing was measured: neither rewarded nor punished. */
    private static final double NEUTRAL = 0.5;

    private final Texts texts;

    public RoutingEngine(Texts texts) {
        this.texts = texts;
    }

    /**
     * @param sizeBytes     size of the file
     * @param states        live state of every storage
     * @param adapterLimits technical size limits of the adapters themselves (for example the
     *                      Database adapter's configured maximum); the stricter limit applies
     * @param now           moment of the decision (used to say how old the health information is)
     */
    public RoutingOutcome evaluate(Policy policy, FileCategory category, long sizeBytes,
                                   Map<StorageType, StorageState> states, Map<StorageType, Long> adapterLimits,
                                   Instant now) {
        CategoryPolicy rules = policy.category(category);
        String categoryName = texts.get("category." + category.name());

        List<Candidate> evaluated = new ArrayList<>();
        for (StorageType storage : orderedStorages(rules)) {
            StorageState state = states.getOrDefault(storage, StorageState.unknown());
            Long limit = effectiveLimit(policy, rules, storage, adapterLimits);
            evaluated.add(evaluate(storage, rules, policy.storages().get(storage), categoryName, sizeBytes,
                    limit, state, now));
        }

        Candidate primary = evaluated.get(0);
        Optional<Candidate> chosen = primary.selectable()
                ? Optional.of(primary)
                : evaluated.stream()
                        .filter(candidate -> candidate.role() == Role.FALLBACK && candidate.selectable())
                        // Highest score wins; on equal scores the fallback listed first in the policy wins.
                        .max(Comparator.comparingDouble(Candidate::score)
                                .thenComparing(candidate -> -evaluated.indexOf(candidate)));

        StorageType selected = chosen.map(Candidate::storage).orElse(null);
        List<Candidate> candidates = evaluated.stream()
                .map(c -> new Candidate(c.storage(), c.role(), c.eligible(), c.available(),
                        c.storage() == selected, c.score(), c.factors(), c.reasons()))
                .toList();

        boolean fallbackUsed = selected != null && selected != primary.storage();
        String fallbackReason = fallbackUsed
                ? texts.get("routing.fallbackReason", name(primary.storage()), negatives(primary))
                : null;
        String pendingReason = selected == null
                ? texts.get("routing.pendingReason", candidates.stream()
                        .map(c -> name(c.storage()) + " — " + negatives(c))
                        .collect(Collectors.joining("; ")))
                : null;
        String explanation = explain(candidates, selected, categoryName, sizeBytes, rules.constraints(),
                fallbackReason, pendingReason);
        return new RoutingOutcome(candidates, selected, fallbackUsed, fallbackReason, pendingReason, explanation);
    }

    /** Size limit that applies to this storage for this category, or {@code null} for none. */
    public static Long effectiveLimit(Policy policy, CategoryPolicy rules, StorageType storage,
                                      Map<StorageType, Long> adapterLimits) {
        Long limit = policy.storages().get(storage).maxSizeBytes();
        Map<StorageType, Long> overrides = rules.constraints().maxSizeBytes();
        limit = stricter(limit, overrides == null ? null : overrides.get(storage));
        return stricter(limit, adapterLimits.get(storage));
    }

    private Candidate evaluate(StorageType storage, CategoryPolicy rules, StorageCapabilities capabilities,
                               String categoryName, long sizeBytes, Long limit, StorageState state, Instant now) {
        List<Reason> reasons = new ArrayList<>();
        Constraints needs = rules.constraints();
        boolean eligible = true;

        // Place in the category's policy.
        Role role;
        double preference;
        int fallbackIndex = rules.fallbacks().indexOf(storage);
        if (storage == rules.primary()) {
            role = Role.PRIMARY;
            preference = 1.0;
            reasons.add(reason(PRIMARY, Kind.POSITIVE, categoryName));
        } else if (fallbackIndex >= 0) {
            role = Role.FALLBACK;
            preference = 1.0 - (fallbackIndex + 1.0) / (rules.fallbacks().size() + 1.0);
            reasons.add(reason(FALLBACK, Kind.INFO, fallbackIndex + 1, categoryName));
        } else {
            role = Role.NOT_IN_POLICY;
            preference = 0.0;
            reasons.add(reason(NOT_IN_POLICY, Kind.NEGATIVE, categoryName));
        }

        // Hard requirement: size.
        double sizeFit;
        if (limit == null) {
            sizeFit = 1.0;
            reasons.add(reason(SIZE_UNLIMITED, Kind.POSITIVE, Sizes.format(sizeBytes)));
        } else if (sizeBytes <= limit) {
            sizeFit = 1.0 - (double) sizeBytes / limit;
            reasons.add(reason(SIZE_OK, Kind.POSITIVE, Sizes.format(sizeBytes), Sizes.format(limit)));
        } else {
            sizeFit = 0.0;
            eligible = false;
            reasons.add(reason(SIZE_EXCEEDED, Kind.NEGATIVE, Sizes.format(sizeBytes), Sizes.format(limit),
                    name(storage)));
        }

        // Hard requirements: capabilities the category needs.
        if (needs.needsResumable()) {
            eligible &= require(capabilities.resumable(), RESUMABLE_OK, RESUMABLE_MISSING, reasons);
        }
        if (needs.needsImmediateRead()) {
            eligible &= require(capabilities.immediateRead(), IMMEDIATE_READ_OK, IMMEDIATE_READ_MISSING, reasons);
        }
        if (needs.idempotent()) {
            eligible &= require(capabilities.idempotentWrites(), IDEMPOTENT_OK, IDEMPOTENT_MISSING, reasons);
        }

        // Live state.
        boolean available = state.isUp();
        if (available) {
            reasons.add(reason(AVAILABLE, Kind.POSITIVE, secondsSince(state.lastCheck(), now)));
        } else if (state.status() == StorageState.Status.DOWN) {
            reasons.add(reason(DOWN, Kind.NEGATIVE, secondsSince(state.lastCheck(), now)));
        } else if (state.status() == StorageState.Status.MAINTENANCE) {
            reasons.add(reason(MAINTENANCE, Kind.NEGATIVE, state.note() == null ? "" : state.note()));
        } else {
            reasons.add(reason(STATE_UNKNOWN, Kind.NEGATIVE));
        }
        double latency;
        if (state.latencyMs() != null) {
            latency = 1.0 / (1.0 + state.latencyMs() / 1000.0);
            reasons.add(reason(LATENCY_MEASURED + "." + state.latencySource(), LATENCY_MEASURED, Kind.INFO,
                    state.latencyMs()));
        } else {
            latency = NEUTRAL;
            reasons.add(reason(LATENCY_UNKNOWN, Kind.INFO));
        }

        Weights weights = rules.weights();
        Map<String, Factor> factors = new LinkedHashMap<>();
        factors.put(FACTOR_SIZE_FIT, new Factor(round(sizeFit), weights.sizeFit()));
        factors.put(FACTOR_RESUMABLE, new Factor(capabilities.resumable() ? 1.0 : 0.0, weights.resumable()));
        factors.put(FACTOR_AVAILABILITY, new Factor(available ? 1.0 : 0.0, weights.availability()));
        factors.put(FACTOR_LATENCY, new Factor(round(latency), weights.measuredLatencyP95()));
        factors.put(FACTOR_PREFERENCE, new Factor(round(preference), weights.policyPreference()));
        double weighted = factors.values().stream().mapToDouble(f -> f.value() * f.weight()).sum();
        double score = round(weighted / weights.total());

        return new Candidate(storage, role, eligible, available, false, score, factors, List.copyOf(reasons));
    }

    private String explain(List<Candidate> candidates, StorageType selected, String categoryName, long sizeBytes,
                           Constraints needs, String fallbackReason, String pendingReason) {
        StringBuilder text = new StringBuilder();
        if (selected == null) {
            text.append(texts.get("routing.explanation.pending", categoryName, Sizes.format(sizeBytes),
                    pendingReason));
            return text.toString();
        }
        Candidate chosen = candidates.stream().filter(Candidate::selected).findFirst().orElseThrow();
        text.append(texts.get("routing.explanation.selected", categoryName, Sizes.format(sizeBytes),
                name(selected), joinAnd(chosen.reasons().stream()
                        .filter(reason -> reason.kind() == Kind.POSITIVE)
                        .map(Reason::text)
                        .toList())));
        if (fallbackReason != null) {
            text.append(' ').append(fallbackReason);
        }
        if (needs.needsEncryption()) {
            text.append(' ').append(texts.get("routing.explanation.encryption"));
        }
        String alternatives = candidates.stream()
                .filter(candidate -> !candidate.selected())
                .filter(candidate -> fallbackReason == null || candidate.role() != Role.PRIMARY)
                .map(candidate -> name(candidate.storage()) + " — " + (candidate.selectable()
                        ? texts.get(chosen.role() == Role.PRIMARY
                                ? "routing.alternative.primaryAvailable"
                                : "routing.alternative.lowerScore", decimal(candidate.score()))
                        : negatives(candidate)))
                .collect(Collectors.joining("; "));
        if (!alternatives.isEmpty()) {
            text.append(' ').append(texts.get("routing.explanation.alternatives", alternatives));
        }
        return text.toString();
    }

    private boolean require(boolean supported, String okCode, String missingCode, List<Reason> reasons) {
        reasons.add(supported ? reason(okCode, Kind.POSITIVE) : reason(missingCode, Kind.NEGATIVE));
        return supported;
    }

    private Reason reason(String code, Kind kind, Object... args) {
        return new Reason(code, kind, texts.get("routing.reason." + code, args));
    }

    /** Variant whose text key is more specific than the public code. */
    private Reason reason(String textKey, String code, Kind kind, Object arg) {
        return new Reason(code, kind, texts.get("routing.reason." + textKey, arg));
    }

    private String negatives(Candidate candidate) {
        return candidate.reasons().stream()
                .filter(reason -> reason.kind() == Kind.NEGATIVE)
                .map(Reason::text)
                .collect(Collectors.joining(", "));
    }

    private String name(StorageType storage) {
        return texts.get("storage." + storage.name());
    }

    private String joinAnd(List<String> parts) {
        if (parts.size() <= 1) {
            return String.join("", parts);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " " + texts.get("routing.and") + " "
                + parts.get(parts.size() - 1);
    }

    /** PRIMARY first, then the fallbacks in policy order, then the storages the policy does not use. */
    private static List<StorageType> orderedStorages(CategoryPolicy rules) {
        List<StorageType> ordered = new ArrayList<>();
        ordered.add(rules.primary());
        ordered.addAll(rules.fallbacks());
        for (StorageType storage : StorageType.values()) {
            if (!ordered.contains(storage)) {
                ordered.add(storage);
            }
        }
        return ordered;
    }

    private static Long stricter(Long a, Long b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Long.valueOf(Math.min(a, b));
    }

    private static long secondsSince(Instant then, Instant now) {
        return then == null ? 0 : Math.max(0, Duration.between(then, now).toSeconds());
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
