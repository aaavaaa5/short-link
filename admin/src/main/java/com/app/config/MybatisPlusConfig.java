package com.app.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置
 * <p>
 * 分页不是自动生效的：BaseMapper.selectPage 依赖 PaginationInnerInterceptor
 * 在 SQL 上追加 LIMIT。不注册这个插件时不会报错，但 selectPage 会退化成
 * "把全部记录装进一个 Page 对象"，分页参数形同虚设。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public IdentifierGenerator identifierGenerator(
            @Value("${shortlink.snowflake.worker-id:1}") long workerId,
            @Value("${shortlink.snowflake.datacenter-id:1}") long datacenterId) {
        if (workerId < 0 || workerId > 31 || datacenterId < 0 || datacenterId > 31) {
            throw new IllegalArgumentException("Snowflake workerId/datacenterId 必须在 0~31 之间");
        }
        return new DefaultIdentifierGenerator(workerId, datacenterId);
    }
}
