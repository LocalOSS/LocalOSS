package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * S3 key 白名单校验与文件路径安全映射（已确认 Q1：直接 bucket/key 布局，无转义）。
 *
 * <p>规则（计划 3.2 节）：
 * <ol>
 *   <li>key 非空、UTF-8 编码后 ≤ 1024 字节</li>
 *   <li>每段（/ 分隔）UTF-8 长度 ≤ 200 字节，完整绝对路径 ≤ 4000 字符</li>
 *   <li>禁止段：`.`、`..`、空段（尾斜杠 a/ 允许）、NUL 与控制字符（0x00-0x1F、0x7F）、`\`</li>
 *   <li>任意段不得为保留目录名 {@code .localoss}</li>
 *   <li>解析后路径 normalize 后必须仍在 bucket 目录内（防 .. 穿越）</li>
 *   <li>符号链接穿越防护（NOFOLLOW_LINKS + toRealPath 校验）</li>
 * </ol>
 *
 * <p>冲突检测（已确认 R1）：目标 key 与已有文件/目录互为前缀时返回 409 KeyConflict。
 */
@Component
@RequiredArgsConstructor
public class KeyValidator {

    /** 桶内保留元数据目录名 */
    public static final String RESERVED_META_DIR = ".localoss";

    public static final int MAX_KEY_BYTES = 1024;
    public static final int MAX_SEGMENT_BYTES = 200;
    public static final int MAX_PATH_CHARS = 4000;

    private final SystemProperties props;

    /** 校验 bucket 名（S3 命名规则完整版） */
    public void validateBucketName(String bucket) {
        if (bucket == null || bucket.length() < 3 || bucket.length() > 63) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME);
        }
        if (!bucket.matches("^[a-z0-9][a-z0-9.-]*[a-z0-9]$")) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME,
                    "Bucket name must begin and end with a letter or number");
        }
        if (bucket.contains("..")) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME,
                    "Bucket name must not contain two adjacent periods");
        }
        if (bucket.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME,
                    "Bucket name must not be formatted as an IP address");
        }
        if (bucket.startsWith("xn--") || bucket.startsWith("sthree-") || bucket.startsWith("amzn-s3-demo-")) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME, "Bucket name uses a reserved prefix");
        }
        if (bucket.endsWith("-s3alias") || bucket.endsWith("--ol-s3") || bucket.endsWith(".mrap")
                || bucket.endsWith("--x-s3") || bucket.endsWith("--table-s3")) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME, "Bucket name uses a reserved suffix");
        }
        if (bucket.equals(RESERVED_META_DIR)) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME, "Bucket name is reserved");
        }
    }

    /**
     * 校验 key 合法性（非法则抛 400 InvalidArgument）。
     */
    public void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Object key must not be empty");
        }
        if (key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Object key exceeds maximum length of " + MAX_KEY_BYTES + " bytes");
        }
        if (key.length() > MAX_PATH_CHARS) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Object key too long");
        }
        String[] segments = key.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            boolean last = i == segments.length - 1;
            // 尾斜杠允许产生空段；中间空段（a//b）拒绝
            if (seg.isEmpty()) {
                if (!last) {
                    throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Object key contains empty segment");
                }
                continue;
            }
            if (seg.equals(".") || seg.equals("..")) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "Object key segment must not be . or ..");
            }
            if (seg.equals(RESERVED_META_DIR)) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "Object key segment '" + RESERVED_META_DIR + "' is reserved");
            }
            if (seg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "Object key segment exceeds " + MAX_SEGMENT_BYTES + " bytes");
            }
            for (int j = 0; j < seg.length(); j++) {
                char c = seg.charAt(j);
                if (c == '\\' || c == 0 || (c < 0x20) || c == 0x7F) {
                    throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                            "Object key contains control character or backslash");
                }
            }
        }
    }

    /**
     * 将 bucket + key 映射为安全文件路径。校验不通过抛异常。
     */
    public Path toFilePath(String bucket, String key) {
        validateBucketName(bucket);
        validateKey(key);
        Path bucketRoot = Paths.get(props.getDataPath(), bucket).normalize();
        Path target;
        if (key.endsWith("/")) {
            // 目录型零字节对象：目录即对象
            target = bucketRoot.resolve(key.substring(0, key.length() - 1)).normalize();
        } else {
            target = bucketRoot.resolve(key).normalize();
        }
        if (!target.startsWith(bucketRoot)) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Object key resolves outside the bucket directory");
        }
        return target;
    }

    public Path bucketDir(String bucket) {
        validateBucketName(bucket);
        return Paths.get(props.getDataPath(), bucket).normalize();
    }

    /**
     * 同名冲突检测（R1）：目标 key 与已有文件/目录互为前缀。
     *
     * <p>目标 key 是文件但已有同名目录，或目标 key 的某个父路径是已有文件 → 冲突。
     */
    public void checkConflict(Path bucketRoot, Path target) {
        Path rel = bucketRoot.relativize(target);
        // 逐级检查：从 bucketRoot 往下，任何前缀若是普通文件则冲突
        Path cur = bucketRoot;
        for (int i = 0; i < rel.getNameCount(); i++) {
            cur = cur.resolve(rel.getName(i));
            if (Files.isRegularFile(cur, LinkOption.NOFOLLOW_LINKS)) {
                if (!cur.equals(target)) {
                    throw new S3Exception(S3ErrorCode.KEY_CONFLICT,
                            "Existing object file blocks this key prefix: " + rel.subpath(0, i + 1));
                }
                // cur == target，是文件写文件，正常
                return;
            }
        }
        // 目标位置已是目录（即 a/b 已存在，要写文件 a）→ 冲突
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new S3Exception(S3ErrorCode.KEY_CONFLICT,
                    "Existing directory blocks this key: " + bucketRoot.relativize(target));
        }
        // 目标是普通文件：覆盖 OK
    }

    /**
     * 符号链接穿越防护：确保 real path 仍在 bucket root 内。
     */
    /**
     * 父目录符号链接防护（PUT 路径）：检查 target 已存在的最近祖先的真实路径
     * 是否仍在 bucket 内。target 自身可以不存在（新建对象）。
     */
    public void checkParentSymlinkEscape(Path bucketRoot, Path target) {
        try {
            // 找到 target 最深已存在的祖先
            Path existing = target;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || existing.equals(bucketRoot)) {
                return;
            }
            Path real = existing.toRealPath();
            Path realRoot = bucketRoot.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                        "Symlink traversal detected");
            }
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "Failed to resolve real path", e);
        }
    }

    public void checkSymlinkEscape(Path bucketRoot, Path target) {
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Path real = target.toRealPath();
                Path realRoot = bucketRoot.toRealPath();
                if (!real.startsWith(realRoot)) {
                    throw new S3Exception(S3ErrorCode.ACCESS_DENIED, "Symlink traversal detected");
                }
            }
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "Failed to resolve real path", e);
        }
    }
}
