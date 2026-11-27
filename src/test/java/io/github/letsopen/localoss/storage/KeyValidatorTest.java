package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3Exception;
import io.github.letsopen.localoss.error.S3ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KeyValidator 单元测试（计划 8.1 节）。
 */
class KeyValidatorTest {

    @TempDir
    Path tmp;

    private SystemProperties props;
    private KeyValidator validator;

    @BeforeEach
    void setUp() {
        props = new SystemProperties();
        props.setDataPath(tmp.toString() + "/");
        validator = new KeyValidator(props);
    }

    // ==================== bucket name ====================

    @Test
    void validBucketNames() {
        for (String b : new String[]{"abc", "my-bucket", "my.bucket", "a1b2c3", "000"}) {
            assertDoesNotThrow(() -> validator.validateBucketName(b), b);
        }
    }

    @Test
    void invalidBucketNames() {
        assertThrows(S3Exception.class, () -> validator.validateBucketName("aa"));          // too short
        assertThrows(S3Exception.class, () -> validator.validateBucketName("a".repeat(64))); // too long
        assertThrows(S3Exception.class, () -> validator.validateBucketName("-abc"));      // leading dash
        assertThrows(S3Exception.class, () -> validator.validateBucketName("abc-"));      // trailing dash
        assertThrows(S3Exception.class, () -> validator.validateBucketName("192.168.5.4"));// IP
        assertThrows(S3Exception.class, () -> validator.validateBucketName("ex..ample")); // adjacent dots
        assertThrows(S3Exception.class, () -> validator.validateBucketName("xn--foo"));
        assertThrows(S3Exception.class, () -> validator.validateBucketName(".localoss"));
        assertThrows(S3Exception.class, () -> validator.validateBucketName("MyBucket"));  // uppercase
        assertThrows(S3Exception.class, () -> validator.validateBucketName("my_bucket")); // underscore
    }

    // ==================== key validation ====================

    @Test
    void validKeys() {
        for (String k : new String[]{"a.jpg", "photos/2026/a.jpg", "my.great_photos-2014/jan/myvacation.jpg",
                "a/b/c/d/e", "中文/文件名.txt", "spaces in key.txt", "plus+key", "a/", "123"}) {
            assertDoesNotThrow(() -> validator.validateKey(k), k);
        }
    }

    @Test
    void invalidKeys_empty() {
        assertThrows(S3Exception.class, () -> validator.validateKey(null));
        assertThrows(S3Exception.class, () -> validator.validateKey(""));
    }

    @Test
    void invalidKeys_dotSegments() {
        assertThrows(S3Exception.class, () -> validator.validateKey("a/../b"));
        assertThrows(S3Exception.class, () -> validator.validateKey("../a"));
        assertThrows(S3Exception.class, () -> validator.validateKey("./a"));
        assertThrows(S3Exception.class, () -> validator.validateKey("a/./b"));
    }

    @Test
    void invalidKeys_emptySegments() {
        assertThrows(S3Exception.class, () -> validator.validateKey("a//b"));
        assertThrows(S3Exception.class, () -> validator.validateKey("a//"));
    }

    @Test
    void invalidKeys_controlAndBackslash() {
        assertThrows(S3Exception.class, () -> validator.validateKey("a\\b"));
        assertThrows(S3Exception.class, () -> validator.validateKey("a\u0000b"));
        assertThrows(S3Exception.class, () -> validator.validateKey("a\u0007b"));
    }

    @Test
    void invalidKeys_reservedSegment() {
        assertThrows(S3Exception.class, () -> validator.validateKey(".localoss/config"));
        assertThrows(S3Exception.class, () -> validator.validateKey("a/.localoss/b"));
    }

    @Test
    void invalidKeys_tooLong() {
        assertThrows(S3Exception.class, () -> validator.validateKey("a".repeat(1025)));
        assertThrows(S3Exception.class, () -> validator.validateKey("a/" + "b".repeat(201)));
        assertDoesNotThrow(() -> validator.validateKey("a/" + "b".repeat(200)));
    }

    // ==================== path mapping ====================

    @Test
    void toFilePath_mapsUnderBucket() {
        Path p = validator.toFilePath("my-bucket", "photos/2026/a.jpg");
        assertTrue(p.startsWith(validator.bucketDir("my-bucket")));
        assertEquals(validator.bucketDir("my-bucket").resolve("photos/2026/a.jpg"), p);
    }

    @Test
    void toFilePath_trailingSlash_mapsToDirectory() {
        Path p = validator.toFilePath("my-bucket", "photos/");
        assertEquals(validator.bucketDir("my-bucket").resolve("photos"), p);
    }

    @Test
    void toFilePath_rejectsTraversal() {
        assertThrows(S3Exception.class, () -> validator.toFilePath("my-bucket", "../escape"));
        // encoded dots are rejected at validate stage
    }

    // ==================== conflict detection ====================

    @Test
    void conflict_prefixFileBlocksChild() throws Exception {
        Path bucket = validator.bucketDir("bkt");
        java.nio.file.Files.createDirectories(bucket);
        java.nio.file.Files.writeString(bucket.resolve("a"), "data");
        // a/b conflicts: a is a file
        S3Exception e = assertThrows(S3Exception.class,
                () -> validator.checkConflict(bucket, bucket.resolve("a/b")));
        assertEquals(S3ErrorCode.KEY_CONFLICT, e.getErrorCode());
    }

    @Test
    void noConflict_fileOverwrite() throws Exception {
        Path bucket = validator.bucketDir("bkt");
        java.nio.file.Files.createDirectories(bucket);
        java.nio.file.Files.writeString(bucket.resolve("a"), "data");
        assertDoesNotThrow(() -> validator.checkConflict(bucket, bucket.resolve("a")));
    }

    @Test
    void noConflict_deepChildUnderDir() throws Exception {
        Path bucket = validator.bucketDir("bkt");
        java.nio.file.Files.createDirectories(bucket.resolve("a/b"));
        assertDoesNotThrow(() -> validator.checkConflict(bucket, bucket.resolve("a/b/c.txt")));
        // 写文件型 key "a" 到已有目录 a/ 位置 → 冲突（Q1 直接布局：a 与 a/ 不能共存）
        assertThrows(S3Exception.class,
                () -> validator.checkConflict(bucket, bucket.resolve("a")));
    }
}
