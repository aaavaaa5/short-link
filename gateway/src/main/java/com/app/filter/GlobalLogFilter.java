package com.app.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全局请求日志过滤器
 * <p>
 * 记录每个请求的方法、路径、状态码、耗时。
 * 注意：日志量较大，生产环境建议采样（如 10% 或只记录非 2xx）。
 */
@Component
public class GlobalLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GlobalLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long duration = System.currentTimeMillis() - start;
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
            log.info("{} {} → {} ({}ms)", method, path, status, duration);
        }));
    }

    @Override
    public int getOrder() {
        // 优先执行日志，便于排查
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
