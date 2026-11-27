package io.github.letsopen.localoss.auth;

import io.github.letsopen.localoss.config.SystemProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * aws-chunked 解码与 chunk 验签测试。
 */
class AwsChunkedDecoderTest {

    private SystemProperties props;
    private AwsChunkedDecoder decoder;

    // 与 SigV4VerifierTest 一致的样例密钥
    private static final String SK = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String DATE = "20261124T010203Z";
    private static final String SCOPE = "20261124/us-east-1/s3/aws4_request";

    @BeforeEach
    void setUp() {
        props = new SystemProperties();
        decoder = new AwsChunkedDecoder(props);
    }

    private static byte[] signingKey() {
        return SigV4Verifier.publicSigningKey(SK, "20261124", "us-east-1");
    }

    private String chunkSignature(String prevSignature, byte[] data) {
        String stringToSign = "AWS4-HMAC-SHA256-PAYLOAD\n"
                + DATE + "\n"
                + SCOPE + "\n"
                + prevSignature + "\n"
                + SigV4Verifier.hex(SigV4Verifier.sha256(""))
                + "\n" + SigV4Verifier.hex(SigV4Verifier.sha256Bytes(data));
        return SigV4Verifier.hex(SigV4Verifier.hmacSha256(signingKey(), stringToSign));
    }

    @Test
    void decode_signedChunks() throws Exception {
        byte[] data1 = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "chunked world".getBytes(StandardCharsets.UTF_8);
        String seed = "seed0000000000000000000000000000000000000000000000000000000000";

        String sig1 = chunkSignature(seed, data1);
        String sig2 = chunkSignature(sig1, data2);
        String sigFinal = chunkSignature(sig2, new byte[0]);

        StringBuilder wire = new StringBuilder();
        wire.append(Integer.toHexString(data1.length)).append(";chunk-signature=").append(sig1)
                .append("\r\n").append(new String(data1, StandardCharsets.UTF_8)).append("\r\n");
        wire.append(Integer.toHexString(data2.length)).append(";chunk-signature=").append(sig2)
                .append("\r\n").append(new String(data2, StandardCharsets.UTF_8)).append("\r\n");
        wire.append("0;chunk-signature=").append(sigFinal).append("\r\n");

        InputStream decoded = decoder.decode(
                new ByteArrayInputStream(wire.toString().getBytes(StandardCharsets.UTF_8)),
                "STREAMING-AWS4-HMAC-SHA256-PAYLOAD",
                seed, DATE, signingKey(), SCOPE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        decoded.transferTo(out);
        assertEquals("hello chunked world", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void decode_signedChunks_badSignature_rejected() throws Exception {
        byte[] data1 = "hello".getBytes(StandardCharsets.UTF_8);
        String seed = "seed0000000000000000000000000000000000000000000000000000000000";
        String badSig = "0".repeat(64);

        StringBuilder wire = new StringBuilder();
        wire.append(Integer.toHexString(data1.length)).append(";chunk-signature=").append(badSig)
                .append("\r\n").append(new String(data1, StandardCharsets.UTF_8)).append("\r\n");
        wire.append("0;chunk-signature=").append(badSig).append("\r\n");

        InputStream decoded = decoder.decode(
                new ByteArrayInputStream(wire.toString().getBytes(StandardCharsets.UTF_8)),
                "STREAMING-AWS4-HMAC-SHA256-PAYLOAD",
                seed, DATE, signingKey(), SCOPE);

        assertThrows(io.github.letsopen.localoss.error.S3Exception.class, () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            decoded.transferTo(out);
        });
    }

    @Test
    void decode_unsignedWithTrailer() throws Exception {
        byte[] data = "plain payload".getBytes(StandardCharsets.UTF_8);
        StringBuilder wire = new StringBuilder();
        wire.append(Integer.toHexString(data.length)).append("\r\n")
                .append(new String(data, StandardCharsets.UTF_8)).append("\r\n");
        wire.append("0\r\n");
        wire.append("x-amz-checksum-crc32:AAAAAA==\r\n");
        wire.append("\r\n");

        InputStream decoded = decoder.decode(
                new ByteArrayInputStream(wire.toString().getBytes(StandardCharsets.UTF_8)),
                "STREAMING-UNSIGNED-PAYLOAD-TRAILER",
                null, null, null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        decoded.transferTo(out);
        assertEquals("plain payload", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void decode_emptyChunks() throws Exception {
        String seed = "seed0000000000000000000000000000000000000000000000000000000000";
        String sigFinal = chunkSignature(seed, new byte[0]);
        String wire = "0;chunk-signature=" + sigFinal + "\r\n";
        InputStream decoded = decoder.decode(
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),
                "STREAMING-AWS4-HMAC-SHA256-PAYLOAD",
                seed, DATE, signingKey(), SCOPE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        decoded.transferTo(out);
        assertEquals(0, out.size());
    }

    @Test
    void headerHelpers() {
        assertTrue(AwsChunkedDecoder.isSignedChunkHeader("STREAMING-AWS4-HMAC-SHA256-PAYLOAD"));
        assertTrue(AwsChunkedDecoder.isSignedChunkHeader("STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER"));
        assertFalse(AwsChunkedDecoder.isSignedChunkHeader("STREAMING-UNSIGNED-PAYLOAD-TRAILER"));
        assertTrue(AwsChunkedDecoder.hasTrailerHeader("STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER"));
        assertTrue(AwsChunkedDecoder.hasTrailerHeader("STREAMING-UNSIGNED-PAYLOAD-TRAILER"));
        assertFalse(AwsChunkedDecoder.hasTrailerHeader("STREAMING-AWS4-HMAC-SHA256-PAYLOAD"));
    }
}
