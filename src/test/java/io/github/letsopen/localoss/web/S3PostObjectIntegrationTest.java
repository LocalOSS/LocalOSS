package io.github.letsopen.localoss.web;

import io.github.letsopen.localoss.LocalOssApplication;
import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.auth.SigV4Verifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3c+：POST Object 浏览器表单上传测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalOssApplication.class)
class S3PostObjectIntegrationTest {

    @TempDir
    static Path dataDir;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SystemProperties props;

    private static final String AK = "TESTAK1234567890";
    private static final String SK = "test-secret-key-0000000000000000";
    private static final String REGION = "us-east-1";

    @BeforeEach
    void setUp() {
        props.setDataPath(dataDir.toString() + "/");
        props.setAccessMode(io.github.letsopen.localoss.config.AccessMode.READ_WRITE);
        props.setAccessKey("");
        props.setSecretKey("");
        props.setRegion(REGION);
        rest.exchange("/post-bkt", HttpMethod.PUT, HttpEntity.EMPTY, Void.class);
    }

    @AfterEach
    void tearDown() {
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

    private MultiValueMap<String, Object> buildForm(String key, byte[] file,
                                                    String policyB64, String signature,
                                                    boolean withAuth, String successStatus,
                                                    String... metaKv) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        if (withAuth) {
            form.add("key", key);
            form.add("x-amz-algorithm", "AWS4-HMAC-SHA256");
            Instant now = Instant.now();
            String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC).format(now);
            String dateStamp = amzDate.substring(0, 8);
            form.add("x-amz-credential", AK + "/" + dateStamp + "/" + REGION + "/s3/aws4_request");
            form.add("x-amz-date", amzDate);
            if (policyB64 != null) {
                form.add("policy", policyB64);
            }
            if (signature != null) {
                form.add("x-amz-signature", signature);
            }
        } else {
            form.add("key", key);
        }
        if (successStatus != null) {
            form.add("success_action_status", successStatus);
        }
        for (int i = 0; i + 1 < metaKv.length; i += 2) {
            form.add("x-amz-meta-" + metaKv[i], metaKv[i + 1]);
        }
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        form.add("file", new HttpEntity<>(file, fileHeaders));
        return form;
    }

    /** 构造 policy + 签名 */
    private String[] buildPolicyAndSignature(String bucket) throws Exception {
        Instant now = Instant.now();
        String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(now);
        String dateStamp = amzDate.substring(0, 8);
        String expiration = now.plusSeconds(600)
                .atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        String policyJson = "{\"expiration\":\"" + expiration + "\",\"conditions\":["
                + "{\"bucket\":\"" + bucket + "\"},"
                + "[\"starts-with\",\"$key\",\"\"],"
                + "[\"content-length-range\",1,1048576]"
                + "]}";
        String policyB64 = Base64.getEncoder().encodeToString(
                policyJson.getBytes(StandardCharsets.UTF_8));
        byte[] key = SigV4Verifier.publicSigningKey(SK, dateStamp, REGION);
        String signature = SigV4Verifier.publicHmacHex(key, policyB64);
        return new String[]{policyB64, signature};
    }

    @Test
    void postObject_anonymous_success201() {
        byte[] file = "posted content".getBytes(StandardCharsets.UTF_8);
        var form = buildForm("docs/hello.txt", file, null, null, false, "201");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> r = rest.postForEntity("/post-bkt", new HttpEntity<>(form, h),
                String.class);
        assertEquals(201, r.getStatusCode().value());
        assertTrue(r.getBody().contains("PostResponse"));
        assertTrue(r.getBody().contains("<Key>docs/hello.txt</Key>"));

        ResponseEntity<byte[]> get = rest.getForEntity("/post-bkt/docs/hello.txt", byte[].class);
        assertEquals(200, get.getStatusCode().value());
        assertArrayEquals(file, get.getBody());
    }

    @Test
    void postObject_signed_success204_withMeta() throws Exception {
        props.setAccessKey(AK);
        props.setSecretKey(SK);
        String[] ps = buildPolicyAndSignature("post-bkt");
        byte[] file = "signed post".getBytes(StandardCharsets.UTF_8);
        var form = buildForm("signed.txt", file, ps[0], ps[1], true, "204", "owner", "carol");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Void> r = rest.postForEntity("/post-bkt", new HttpEntity<>(form, h),
                Void.class);
        assertEquals(204, r.getStatusCode().value());
        assertNotNull(r.getHeaders().getFirst("ETag"));

        props.setAccessKey("");  // 关闭认证以便校验落盘结果
        ResponseEntity<Void> head = rest.exchange("/post-bkt/signed.txt", HttpMethod.HEAD,
                HttpEntity.EMPTY, Void.class);
        assertEquals(200, head.getStatusCode().value());
        assertEquals("carol", head.getHeaders().getFirst("x-amz-meta-owner"));
    }

    @Test
    void postObject_signed_badSignature_403() throws Exception {
        props.setAccessKey(AK);
        props.setSecretKey(SK);
        String[] ps = buildPolicyAndSignature("post-bkt");
        byte[] file = "x".getBytes(StandardCharsets.UTF_8);
        var form = buildForm("bad.txt", file, ps[0], "0".repeat(64), true, "204");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> r = rest.postForEntity("/post-bkt", new HttpEntity<>(form, h),
                String.class);
        assertEquals(403, r.getStatusCode().value());
        assertTrue(r.getBody().contains("SignatureDoesNotMatch"));
    }

    @Test
    void postObject_policyBucketMismatch_403() throws Exception {
        props.setAccessKey(AK);
        props.setSecretKey(SK);
        // policy 声明别的 bucket
        String[] ps = buildPolicyAndSignature("other-bucket");
        byte[] file = "x".getBytes(StandardCharsets.UTF_8);
        var form = buildForm("mismatch.txt", file, ps[0], ps[1], true, "204");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> r = rest.postForEntity("/post-bkt", new HttpEntity<>(form, h),
                String.class);
        assertEquals(403, r.getStatusCode().value());
    }

    @Test
    void postObject_missingFile_400() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("key", "nofile.txt");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> r = rest.postForEntity("/post-bkt", new HttpEntity<>(form, h),
                String.class);
        assertEquals(400, r.getStatusCode().value());
        assertTrue(r.getBody().contains("InvalidArgument"));
    }

    @Test
    void postObject_contentLengthRange_violated_400() throws Exception {
        props.setAccessKey(AK);
        props.setSecretKey(SK);
        String[] ps = buildPolicyAndSignature("post-bkt");
        byte[] file = new byte[1048577]; // 超过 policy 的 1MiB 上限
        var form = buildForm("big.txt", file, ps[0], ps[1], true, "204");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> r = rest.postForEntity("/post-bkt", new HttpEntity<>(form, h),
                String.class);
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void postObject_keyPatternWithFilename() {
        byte[] file = "fn".getBytes(StandardCharsets.UTF_8);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("key", "uploads/${filename}");
        form.add("success_action_status", "201");
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.TEXT_PLAIN);
        // 用 Resource 模拟带 filename 的 part
        org.springframework.core.io.ByteArrayResource res =
                new org.springframework.core.io.ByteArrayResource(file) {
                    @Override
                    public String getFilename() {
                        return "my-report.txt";
                    }
                };
        form.add("file", res);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> r = rest.postForEntity("/post-bkt", new HttpEntity<>(form, h),
                String.class);
        assertEquals(201, r.getStatusCode().value());
        assertTrue(r.getBody().contains("<Key>uploads/my-report.txt</Key>"), r.getBody());
    }
}
