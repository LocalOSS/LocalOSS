package io.github.letsopen.localoss.storage;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ACL 有限执行（单租户模型，计划 2.9 节确认）。
 *
 * <p>语义边界：单租户下 ACL 的实际意义只有「是否可匿名读」。因此：
 * <ul>
 *   <li>{@code public-read} → 匿名可读（对象级覆盖 allow-anonymous-read 全局开关）</li>
 *   <li>{@code private}（默认）→ 仅签名可读</li>
 *   <li>{@code bucket-owner-full-control} / {@code bucket-owner-read} / {@code authenticated-read} /
 *       {@code aws-exec-read} → 单租户下等效 private，原样存储返回</li>
 *   <li>{@code log-delivery-write} / {@code public-read-write} → 拒绝（危险语义，本地无意义）</li>
 * </ul>
 *
 * <p>写动作（public-read-write / grant-* 头）一律拒绝而非静默降级，避免假兼容。
 */
@Slf4j
@Component
public class AclEvaluator {

    public static final String PRIVATE = "private";
    public static final String PUBLIC_READ = "public-read";

    /** 接受并存储的 canned ACL（有限语义执行） */
    private static final Set<String> SUPPORTED = Set.of(
            PRIVATE, PUBLIC_READ, "public-read-write", "authenticated-read",
            "aws-exec-read", "bucket-owner-read", "bucket-owner-full-control");

    private final SystemProperties props;

    public AclEvaluator(SystemProperties props) {
        this.props = props;
    }

    /** 解析 x-amz-acl 头；null 时返回 null（调用方回退默认） */
    public String parseCannedHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String acl = header.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(acl)) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Unsupported canned ACL: " + acl);
        }
        // 危险语义明确拒绝
        if ("public-read-write".equals(acl)) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "public-read-write ACL is not supported in single-tenant mode");
        }
        return acl;
    }

    /** grant-* 头一律拒绝（避免静默忽略授权语义） */
    public void rejectGrantHeaders(Map<String, String> headers) {
        for (String h : new String[]{"x-amz-grant-read", "x-amz-grant-write",
                "x-amz-grant-read-acp", "x-amz-grant-write-acp", "x-amz-grant-full-control"}) {
            if (headers.containsKey(h) && !headers.get(h).isBlank()) {
                throw new S3Exception(S3ErrorCode.NOT_IMPLEMENTED,
                        "x-amz-grant-* ACL grants are not supported");
            }
        }
    }

    /** 给定 canned ACL，对象/桶是否可匿名读；未设置时回退全局开关 */
    public boolean isPublicRead(String cannedAcl, boolean defaultIfNull) {
        if (cannedAcl == null || cannedAcl.isBlank()) {
            return defaultIfNull;
        }
        return PUBLIC_READ.equals(cannedAcl);
    }

    /** 全局匿名读开关（语义：未显式设置 ACL 的对象按全局开关） */
    public boolean globalAnonymousRead() {
        return props.isAllowAnonymousRead();
    }
}
