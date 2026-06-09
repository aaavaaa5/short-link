package com.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shortLinkOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("短链接服务 API")
                        .description("短链接跳转、管理后台、统计查询接口")
                        .version("1.0")
                        .contact(new Contact()
                                .name("开发者")
                                .email("dev@shortlink.com")));
    }
}
