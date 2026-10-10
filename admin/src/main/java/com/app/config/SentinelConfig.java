package com.app.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 配置（第二层：接口级限流）
 * <p>
 * 与第一层 UserFlowRiskCntlFilter（用户级，20次/秒）互补：
 * <ul>
 *   <li>第一层：按用户限流，拦截所有管理接口</li>
 *   <li>第二层：按接口限流，只拦截创建短链接接口（QPS=1）</li>
 * </ul>
 */
@Configuration
public class SentinelConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelConfig.class);

    /** 创建短链接接口的 QPS 上限 */
    private static final double CREATE_LINK_QPS = 1.0;

    // ==================== Bean ====================

    /**
     * 注册 SentinelResourceAspect，使 @SentinelResource 注解生效
     */
    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    // ==================== 流控规则初始化 ====================

    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        // 创建短链接接口 → QPS=1
        FlowRule createLinkRule = new FlowRule();
        createLinkRule.setResource("create_short-link");
        createLinkRule.setGrade(1);              // QPS 模式
        createLinkRule.setCount(CREATE_LINK_QPS);
        rules.add(createLinkRule);

        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 流控规则已加载：create_short-link QPS={}", CREATE_LINK_QPS);
    }
}
