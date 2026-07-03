package com.enterprise.ems.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Bootstraps the Eureka service registry. {@code @EnableEurekaServer} turns
 * this plain Spring Boot application into a full Eureka registry: it starts
 * accepting registrations, heartbeats, and lookups from every other service
 * in the platform on {@code /eureka/**}.
 */
@EnableEurekaServer
@SpringBootApplication
public class DiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
