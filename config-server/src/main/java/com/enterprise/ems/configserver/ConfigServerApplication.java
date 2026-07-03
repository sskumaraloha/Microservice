package com.enterprise.ems.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Serves every other service's {@code application.yml} from a single,
 * centrally managed source (see {@code src/main/resources/config-repo}) so
 * environment-specific settings and secrets never get copy-pasted across
 * services.
 */
@EnableConfigServer
@SpringBootApplication
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
