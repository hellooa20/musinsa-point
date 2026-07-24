package com.musinsapayments.point.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI pointOpenApi() {
        return new OpenAPI().info(new Info()
                .title("포인트 시스템 API")
                .version("v1")
                .description("적립, 적립취소, 사용, 사용취소 및 원장 추적 API"));
    }
}
