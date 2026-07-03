package com.enterprise.ems.department.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI departmentServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Department Service API")
                .description("Department CRUD, organizational hierarchy, and local statistics.")
                .version("v1"));
    }
}
