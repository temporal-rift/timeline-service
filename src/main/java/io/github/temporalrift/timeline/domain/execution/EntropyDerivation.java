package io.github.temporalrift.timeline.domain.execution;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * SHA256_V1: every value is a SHA-256 digest over an unambiguous length-prefixed UTF-8 tuple, so purposes,
 * coordinates and draw indices can never alias one another.
 */
public final class EntropyDerivation {

    private EntropyDerivation() {}

    public static long word(ExecutionContext context, String purpose, String coordinate, int drawIndex) {
        var digest = digest(
                context.seed().decimal(),
                context.entropyVersion().name(),
                purpose,
                coordinate,
                Integer.toString(drawIndex));
        return ByteBuffer.wrap(digest).getLong();
    }

    public static UUID identity(ExecutionContext context, String kind, String coordinate) {
        var digest =
                digest(context.caseKey().toString(), context.entropyVersion().name(), "identity", kind, coordinate);
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        var buffer = ByteBuffer.wrap(digest);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] digest(String... parts) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                var bytes = part.getBytes(StandardCharsets.UTF_8);
                sha256.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                sha256.update(bytes);
            }
            return sha256.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }
}
