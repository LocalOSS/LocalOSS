package io.github.letsopen.localoss.web;

import io.github.letsopen.localoss.LocalOssApplication;
import io.github.letsopen.localoss.auth.SigV4Verifier;
import io.github.letsopen.localoss.config.SystemProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多组 AK/SK 集成测试：每组凭证可限定读写级别。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3MultiCredentialIntegrationTest {

    @TempDir
    static Path dataDir;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SystemProperties props;

    private static final String WRITER_AK = "WRITER000000000000";
    private static final String WRITER_SK = "writer-secret-00000000000000000";
    private static final String READER_AK = "READER000000000000";
    private static final String READER_SK = "reader-secret-00000000000000000";
    private static final String REGION = "us-east-1";

    @BeforeEach
    void setUp() {
        props.setDataPath(dataDir.toString() + "/");
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.READ_WRITE);
        props.setAccessKey(WRITER_AK);
        props.setSecretKey(WRITER_SK);
        props.setRegion(REGION);
        io.github.letsopen.localoss.auth.AccessCredential reader =
                new io.github.letsopen.localoss.auth.AccessCredential();
        reader.setAccessKey(READER_AK);
        reader.setSecretKey(READER_SK);
        reader.setLevel("read-only");
        reader.setDescription("read-only account");
        props.setCredentials(List.of(reader));

        // 关认证预创建桶
        props.setAccessKey("");
        rest.exchange("/mc-bkt", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
        props.setAccessKey(WRITER_AK);
    }

    @AfterEach
    void tearDown() {
        props.setCredentials(List.of());
        props.setAccessKey("");
        props.setSecretKey("");
        try (var s = Files.newDirectoryStream(dataDir)) {
            for (Path p : s) {
                deleteRecursively(p);
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                if (Files.isDirectory(p)) {
                    deleteRecursively(p);
                } else {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
        }
    }

    private int port() {
        String root = rest.getRootUri();
        return Integer.parseInt(root.substring(root.lastIndexOf(':') + 1)
                .replaceAll("[^0-9]", ""));
    }

    /** 用指定 AK/SK 构造预签名 URL */
    private String presignAs(String ak, String sk, String method, String path,
                             long expires) {
        try {
            String host = "localhost:" + port();
            Instant now = Instant.now();
            String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC).format(now);
            String dateStamp = amzDate.substring(0, 8);
            String scope = dateStamp + "/" + REGION + "/s3/aws4_request";
            TreeMap<String, String> params = new TreeMap<>();
            params.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
            params.put("X-Amz-Credential", ak + "/" + scope);
            params.put("X-Amz-Date", amzDate);
            params.put("X-Amz-Expires", String.valueOf(expires));
            params.put("X-Amz-SignedHeaders", "host");
            StringBuilder cq = new StringBuilder();
            for (var e : params.entrySet()) {
                if (cq.length() > 0) {
                    cq.append('&');
                }
                cq.append(S3PresignedIntegrationTest.uriEncode(e.getKey(), true))
                        .append('=').append(S3PresignedIntegrationTest.uriEncode(e.getValue(), true));
            }
            String canonicalRequest = method + "\n/" + path + "\n" + cq + "\n"
                    + "host:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                    + SigV4Verifier.publicHexSha256(canonicalRequest);
            byte[] key = SigV4Verifier.publicSigningKey(sk, dateStamp, REGION);
            String signature = SigV4Verifier.publicHmacHex(key, stringToSign);
            return "http://" + host + "/" + path + "?" + cq + "&X-Amz-Signature=" + signature;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void writer_canWrite_reader_canRead() {
        // writer PUT
        String putUrl = presignAs(WRITER_AK, WRITER_SK, "PUT", "mc-bkt/a.txt", 300);
        ResponseEntity<String> put = rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>("hello"), String.class);
        assertEquals(200, put.getStatusCode().value(), put.getBody());

        // reader GET
        String getUrl = presignAs(READER_AK, READER_SK, "GET", "mc-bkt/a.txt", 300);
        ResponseEntity<String> get = rest.getForEntity(URI.create(getUrl), String.class);
        assertEquals(200, get.getStatusCode().value());
        assertEquals("hello", get.getBody());
    }

    @Test
    void reader_cannotWrite() {
        String putUrl = presignAs(READER_AK, READER_SK, "PUT", "mc-bkt/deny.txt", 300);
        ResponseEntity<String> put = rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>("x"), String.class);
        assertEquals(403, put.getStatusCode().value());
        assertTrue(put.getBody().contains("AccessDenied"));
    }

    @Test
    void reader_cannotDelete() {
        // writer 先写
        String putUrl = presignAs(WRITER_AK, WRITER_SK, "PUT", "mc-bkt/x.txt", 300);
        assertEquals(200, rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>("x"), String.class).getStatusCode().value());
        // reader DELETE 拒绝
        String delUrl = presignAs(READER_AK, READER_SK, "DELETE", "mc-bkt/x.txt", 300);
        ResponseEntity<String> del = rest.exchange(URI.create(delUrl), HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);
        assertEquals(403, del.getStatusCode().value());
        assertTrue(del.getBody().contains("AccessDenied"));
    }

    @Test
    void unknownAccessKey_rejected() {
        String putUrl = presignAs("UNKNOWN0000000000", "whatever", "PUT", "mc-bkt/y.txt", 300);
        ResponseEntity<String> r = rest.exchange(URI.create(putUrl), HttpMethod.PUT,
                new HttpEntity<>("x"), String.class);
        assertEquals(403, r.getStatusCode().value());
        assertTrue(r.getBody().contains("InvalidAccessKeyId"));
    }

    @Test
    void writer_listAllowed_reader_listAllowed() {
        String wUrl = presignAs(WRITER_AK, WRITER_SK, "GET", "mc-bkt", 300);
        assertEquals(200, rest.getForEntity(URI.create(wUrl), String.class).getStatusCode().value());
        String rUrl = presignAs(READER_AK, READER_SK, "GET", "mc-bkt", 300);
        assertEquals(200, rest.getForEntity(URI.create(rUrl), String.class).getStatusCode().value());
    }
}
