package ro.filegrid.routing;

import java.util.List;
import java.util.Map;
import ro.filegrid.storage.StorageType;

/**
 * Result of one evaluation by Agent 01: every storage with its verdict, the
 * storage chosen (if any) and the explanation built from those same reasons.
 *
 * @param candidates     one entry per storage, in the order PRIMARY, fallbacks, others
 * @param selected       chosen storage, or {@code null} when nothing is eligible and available
 * @param fallbackUsed   whether a storage other than the primary was chosen
 * @param fallbackReason why the primary was not used (Romanian), or {@code null}
 * @param pendingReason  why no storage could be chosen (Romanian), or {@code null}
 * @param explanationRo  the text shown in the Decision Explorer
 */
public record RoutingOutcome(List<Candidate> candidates, StorageType selected, boolean fallbackUsed,
                             String fallbackReason, String pendingReason, String explanationRo) {

    public Candidate candidate(StorageType storage) {
        return candidates.stream().filter(c -> c.storage() == storage).findFirst().orElseThrow();
    }

    public enum Role {
        PRIMARY,
        FALLBACK,
        /** Evaluated for transparency, but the category's policy does not allow it. */
        NOT_IN_POLICY
    }

    /**
     * @param eligible  whether the storage can hold this file at all (size and required capabilities)
     * @param available whether the storage answered a recent health probe
     * @param score     weighted score in [0, 1]
     * @param factors   the value and weight of every criterion that produced the score
     * @param reasons   concrete reasons, positive and negative
     */
    public record Candidate(StorageType storage, Role role, boolean eligible, boolean available,
                            boolean selected, double score, Map<String, Factor> factors, List<Reason> reasons) {

        public boolean selectable() {
            return role != Role.NOT_IN_POLICY && eligible && available;
        }

        public boolean hasReason(String code) {
            return reasons.stream().anyMatch(reason -> reason.code().equals(code));
        }
    }

    /**
     * @param value  how well the storage does on this criterion, in [0, 1]
     * @param weight weight of the criterion in the category's policy
     */
    public record Factor(double value, double weight) {
    }

    /**
     * @param code stable identifier
     * @param kind whether the reason speaks for or against the storage
     * @param text Romanian text
     */
    public record Reason(String code, Kind kind, String text) {
    }

    public enum Kind {
        POSITIVE,
        NEGATIVE,
        INFO
    }
}
