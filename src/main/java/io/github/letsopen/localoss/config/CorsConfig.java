package io.github.letsopen.localoss.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 全局 CORS 兜底（按桶规则在 Phase 5 落地）。默认放行所有来源，保持原有行为。
 */
@Configuration
public class CorsConfig {

    private CorsConfiguration buildConfig() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.addAllowedOrigin("*");
        cfg.addAllowedHeader("*");
        cfg.addAllowedMethod("*");
        return cfg;
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfig());
        return new CorsFilter(source);
    }
}
