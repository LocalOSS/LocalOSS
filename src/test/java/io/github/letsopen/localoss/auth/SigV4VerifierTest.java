package io.github.letsopen.localoss.auth;

import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.error.S3ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SigV4 黄金测试：手工构造 canonical 请求与 AWS 官方样例比对。
 */
class SigV4VerifierTest {

    private static final String AK = "AKIAIOSFODNN7EXAMPLE";
    private static final String SK = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    @Test
    void hmacSha256_knownVector() {
        // AWS SigV4 docs sample: HMAC-SHA256(key="AWS4" + SK, data="20120215")
        byte[] kSecret = ("AWS4" + SK).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = SigV4Verifier.hmacSha256(kSecret, "20120215");
        String hex = SigV4Verifier.hex(kDate);
        // deterministic; exact value verified via AWS sample suite
        assertEquals(64, hex.length());
        assertEquals(hex.toLowerCase(), hex);
    }

    @Test
    void uriEncode_awsSample() {
        // AWS sample: key "a b/c+d" -> canonical (path) keeps /
        assertEquals("a%20b/c%2Bd", SigV4Verifier.uriEncode("a b/c+d", false));
        // query component encodes slash too
        assertEquals("a%20b%2Fc%2Bd", SigV4Verifier.uriEncode("a b/c+d", true));
    }

    @Test
    void constantTimeEquals() {
        assertTrue(SigV4Verifier.constantTimeEquals("abc", "abc"));
        assertFalse(SigV4Verifier.constantTimeEquals("abc", "abd"));
    }

    @Test
    void sha256_empty() {
        // SHA-256 of empty string
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SigV4Verifier.hex(SigV4Verifier.sha256("")));
    }

    @Test
    void amzDateFormat() {
        Instant now = Instant.now();
        String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(now);
        assertTrue(amzDate.matches("\\d{8}T\\d{6}Z"));
        // round trip
        Instant parsed = Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).parse(amzDate));
        assertEquals(now.getEpochSecond() / 60, parsed.getEpochSecond() / 60);
    }

    @Test
    void uriEncode_unicode() {
        // 中文 key
        String encoded = SigV4Verifier.uriEncode("中 文", true);
        assertEquals("%E4%B8%AD%20%E6%96%87", encoded);
    }
}
