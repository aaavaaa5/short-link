package com.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) 配置
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shortLinkAdminOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("短链接管理后台 API")
                        .description("短链接创建、修改、删除、分组管理、访问统计接口")
                        .version("1.0")
                        .contact(new Contact()
                                .name("开发者")
                                .email("dev@shortlink.com")));
    }
}
