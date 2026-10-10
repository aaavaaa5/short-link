package com.app.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * <p>
 * 拦截器执行顺序（这里的 order 指拦截器的注册先后，与"限流第几层"不是同一个编号）：
 * <ol>
 *   <li>LoginInterceptor（order=1）— 登录校验，设置 UserContext</li>
 *   <li>UserFlowRiskCntlFilter（order=2）— 用户级限流，三层限流中的第 2 层（依赖 UserContext）</li>
 * </ol>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Autowired
    private UserFlowRiskCntlFilter userFlowRiskCntlFilter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器（order=1：先校验登录 → 设置用户信息）
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login"
                );

        // 用户级限流拦截器（order=2：依赖 UserContext 获取 userId）
        registry.addInterceptor(userFlowRiskCntlFilter)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login"
                );
    }
}
