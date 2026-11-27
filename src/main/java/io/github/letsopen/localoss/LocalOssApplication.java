package io.github.letsopen.localoss;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LocalOssApplication {
    public static void main(String[] args) {
        SpringApplication.run(LocalOssApplication.class, args);
    }
}
