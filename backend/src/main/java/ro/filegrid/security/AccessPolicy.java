package ro.filegrid.security;

/**
 * Authorization decisions. Called wherever a permission will be required, so
 * that real rules can be plugged in later without touching the callers.
 */
public interface AccessPolicy {

    /**
     * @param actor      who asks (from {@link CurrentActorProvider})
     * @param permission permission name, for example {@code pii:read}
     */
    boolean isAllowed(String actor, String permission);
}
