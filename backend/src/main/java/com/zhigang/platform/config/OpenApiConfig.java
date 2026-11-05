package com.zhigang.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI zhigangOpenApi() {
        return new OpenAPI().info(new Info()
                .title("智岗图谱 API")
                .version("1.0.0")
                .description("岗位市场雷达、可信图谱治理与个人职业导航接口"));
    }
}
