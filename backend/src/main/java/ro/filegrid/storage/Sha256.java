package ro.filegrid.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. Hashes are always computed while streaming, never on a full copy. */
public final class Sha256 {

    private Sha256() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    /** Finishes the digest and returns it as lowercase hex. */
    public static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}
