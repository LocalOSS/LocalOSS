package io.github.letsopen.localoss.config;

/**
 * 读写级别求值：全局 access-mode 与凭证级 level 取交集（更严格者生效）。
 */
public final class AccessModeResolver {

    private AccessModeResolver() {
    }

    /**
     * @param global      全局 access-mode
     * @param credentialLevel 凭证级限制（可空）
     * @return 生效级别
     */
    public static AccessMode resolve(AccessMode global, String credentialLevel) {
        if (credentialLevel == null || credentialLevel.isBlank()) {
            return global;
        }
        AccessMode cred;
        try {
            cred = AccessMode.valueOf(credentialLevel.trim().toUpperCase()
                    .replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return global; // 非法值忽略，按全局
        }
        // 交集：更严格者
        if (global == AccessMode.DISABLED || cred == AccessMode.DISABLED) {
            return AccessMode.DISABLED;
        }
        if (global == AccessMode.READ_ONLY || cred == AccessMode.READ_ONLY) {
            return AccessMode.READ_ONLY;
        }
        if (global == AccessMode.WRITE_ONLY || cred == AccessMode.WRITE_ONLY) {
            return AccessMode.WRITE_ONLY;
        }
        return AccessMode.READ_WRITE;
    }
}
