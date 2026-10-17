package io.github.longshou.mcyukkuri.body;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Model identity independent of Git's LF/CRLF checkout conversion.
 * Existing export hashes are retained for provenance.
 * No JSON values, spaces, ordering, or final newlines are discarded.
 */
public record ModelFingerprint(String raw, String lf, String crlf) {
    public static ModelFingerprint of(byte[] bytes) {
        try {
            MessageDigest original = MessageDigest.getInstance("SHA-256");
            MessageDigest unix = MessageDigest.getInstance("SHA-256");
            MessageDigest windows = MessageDigest.getInstance("SHA-256");
            original.update(bytes);
            for (int i = 0; i < bytes.length; i++) {
                byte value = bytes[i];
                if (value == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') continue;
                unix.update(value);
                if (value == '\n') windows.update((byte) '\r');
                windows.update(value);
            }
            var hex = HexFormat.of();
            return new ModelFingerprint(hex.formatHex(original.digest()),
                    hex.formatHex(unix.digest()), hex.formatHex(windows.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public boolean matches(String expected) {
        return raw.equals(expected) || lf.equals(expected) || crlf.equals(expected);
    }
}

