package io.github.letsopen.localoss.auth;

import lombok.Data;

/**
 * 访问凭证（单租户内的多组 AK/SK，每组可限定读写级别）。
 *
 * <p>levels 为空 = 继承全局 access-mode；否则取交集（更严格者生效）。
 */
@Data
public class AccessCredential {

    private String accessKey;
    private String secretKey;

    /** 凭证级读写限制：read-write / read-only / write-only（可空） */
    private String level;

    /** 可选描述 */
    private String description;
}
