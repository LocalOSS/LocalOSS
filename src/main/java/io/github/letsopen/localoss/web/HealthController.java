package io.github.letsopen.localoss.web;

import io.github.letsopen.localoss.config.SystemProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查（已确认 R7：路径 /localoss-health，避免与 bucket 名 localoss 冲突）。
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final SystemProperties props;

    @GetMapping(S3RequestFilter.HEALTH_PATH)
    public Map<String, Object> health() {
        return Map.of(
                "status", props.getAccessMode() == io.github.letsopen.localoss.config.AccessMode.DISABLED
                        ? "DOWN" : "UP",
                "mode", props.getAccessMode().name(),
                "instance", props.getInstanceId()
        );
    }
}
