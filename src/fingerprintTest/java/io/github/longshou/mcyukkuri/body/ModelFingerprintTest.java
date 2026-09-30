package io.github.longshou.mcyukkuri.body;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Dependency-free regression test, run by Gradle check. */
public final class ModelFingerprintTest {
    private static int checks;
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
    }
    private static void check(boolean result, String name) {
        checks++;
        if (!result) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        String lf = "{\n  \"id\": \"灵梦\",\n  \"point\": 1.25, \"escaped\": \"\\r\\n\"\n}\n";
        String crlf = lf.replace("\n", "\r\n");
        String mixed = lf.replaceFirst("\n", "\r\n");
        for (String current : new String[] {lf, crlf, mixed}) {
            var fingerprint = ModelFingerprint.of(bytes(current));
            check(fingerprint.matches(hash(current)), "exact source");
            check(fingerprint.matches(hash(lf)), "LF export");
            check(fingerprint.matches(hash(crlf)), "legacy CRLF export");
            check(!fingerprint.matches(hash(lf.replace("1.25", "1.26"))), "reject geometry drift");
            check(!fingerprint.matches(hash(lf.replace("灵梦", "魔理沙"))), "reject identity drift");
            check(!fingerprint.matches(hash(lf.replace("\\r\\n", "\\n"))), "preserve escaped JSON content");
            check(!fingerprint.matches(hash(lf.stripTrailing())), "preserve final newline");
            check(!fingerprint.matches(hash(lf.replace("  ", " "))), "preserve non-newline whitespace");
            check(!fingerprint.matches(hash(lf.replace("\n", "\r"))), "preserve lone carriage return");
            check(!fingerprint.matches(null), "reject absent fingerprint");
        }
        check(ModelFingerprint.of(bytes("")).matches(hash("")), "empty input");
        System.out.println("Model fingerprint regression: " + checks + " checks passed");
    }
}

