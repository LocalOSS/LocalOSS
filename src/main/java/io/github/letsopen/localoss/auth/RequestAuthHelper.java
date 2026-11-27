package io.github.letsopen.localoss.auth;

import io.github.letsopen.localoss.web.S3RequestContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求凭证判定（供 ACL 匿名读逻辑使用）。
 */
public final class RequestAuthHelper {

    private RequestAuthHelper() {
    }

    /** 请求是否携带任何形式的凭证（Authorization 头 / 预签名参数） */
    public static boolean hasCredentials(HttpServletRequest request, S3RequestContext ctx) {
        if (request.getHeader("Authorization") != null) {
            return true;
        }
        return "AWS4-HMAC-SHA256".equals(ctx.queryParam("X-Amz-Algorithm"));
    }

    /** 桶是否为 public-read（供匿名 List 判定） */
    public static boolean isBucketPublicRead(io.github.letsopen.localoss.storage.StorageEngine storage,
                                             io.github.letsopen.localoss.storage.KeyValidator kv,
                                             String bucket) {
        try {
            return "public-read".equals(
                    storage.getBucketAcl(kv.bucketDir(bucket)));
        } catch (Exception e) {
            return false;
        }
    }
}
