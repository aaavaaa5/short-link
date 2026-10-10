package com.app.config;

import com.app.common.bloom.BloomFilterHelper;
import com.app.entity.LinkEntity;
import com.app.mapper.LinkMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 布隆过滤器历史数据回填
 * <p>
 * project 的读路径在布隆判否时会直接返回 404。如果位图里缺少"已经存在"的短码，
 * 那些短链就会全部访问失败，所以短路逻辑生效前必须把历史数据补齐。
 * <p>
 * 用一个 Redis 标记保证只回填一次；回填过程出错会清掉标记，下次启动自动重试。
 * <p>
 * 部署顺序：先启动 admin 跑完回填，再发布 project 的短路逻辑。
 */
@Component
public class BloomBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BloomBackfillRunner.class);

    /** 回填完成标记，存在即表示已回填过 */
    private static final String BACKFILL_FLAG = "shortlink:bf:backfilled";

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private BloomFilterHelper bloomFilterHelper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(BACKFILL_FLAG, "1");
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }

        try {
            List<LinkEntity> links = linkMapper.selectList(Wrappers.lambdaQuery());
            int count = 0;
            for (LinkEntity link : links) {
                if (link.getShortUri() != null) {
                    bloomFilterHelper.add(link.getShortUri());
                    count++;
                }
            }
            log.info("布隆过滤器回填完成，共 {} 条短链", count);
        } catch (Exception e) {
            // 回填失败则清掉标记，下次启动重试
            redisTemplate.delete(BACKFILL_FLAG);
            log.error("布隆过滤器回填失败，已清除标记等待下次重试", e);
        }
    }
}
