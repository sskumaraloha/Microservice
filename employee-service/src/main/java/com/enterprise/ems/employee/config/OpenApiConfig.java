package com.enterprise.ems.employee.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI employeeServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Employee Service API")
                .description("Employee CRUD, search, pagination, sorting, and document management.")
                .version("v1"));
    }
}
