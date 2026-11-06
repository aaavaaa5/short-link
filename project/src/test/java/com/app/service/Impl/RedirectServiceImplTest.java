package com.app.service.Impl;

import com.app.entity.LinkEntity;
import com.app.entity.LinkRouteEntity;
import com.app.mapper.LinkMapper;
import com.app.mapper.LinkRouteMapper;
import com.app.mq.AccessEventProducer;
import com.app.service.CacheService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedirectServiceImplTest {

    @Mock
    private LinkMapper linkMapper;

    @Mock
    private LinkRouteMapper linkRouteMapper;

    @Mock
    private CacheService cacheService;

    @Mock
    private AccessEventProducer accessEventProducer;

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedirectServiceImpl redirectService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "test"), LinkEntity.class);
        redirectService = new RedirectServiceImpl();
        ReflectionTestUtils.setField(redirectService, "linkMapper", linkMapper);
        ReflectionTestUtils.setField(redirectService, "linkRouteMapper", linkRouteMapper);
        ReflectionTestUtils.setField(redirectService, "cacheService", cacheService);
        ReflectionTestUtils.setField(redirectService, "accessEventProducer", accessEventProducer);
        ReflectionTestUtils.setField(redirectService, "redisTemplate", redisTemplate);
    }

    @Test
    void cacheMissUsesRouteThenShardKeyAndId() {
        when(cacheService.getLinkEntity("abc123")).thenReturn(null);
        when(cacheService.isNullCached("abc123")).thenReturn(false);
        when(cacheService.mightExist("abc123")).thenReturn(true);
        when(linkRouteMapper.selectById("abc123")).thenReturn(route());
        LinkEntity link = link();
        when(linkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(link));

        LinkEntity result = redirectService.getLinkEntity("abc123");

        assertEquals(link, result);
        ArgumentCaptor<Wrapper<LinkEntity>> wrapperCaptor = wrapperCaptor();
        verify(linkMapper).selectList(wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment().toLowerCase();
        assertFalse(sql.contains("short_uri"));
        verify(cacheService).cacheLinkEntity(org.mockito.ArgumentMatchers.eq("abc123"),
                org.mockito.ArgumentMatchers.eq(link), any());
    }

    @Test
    void missingRouteDoesNotQueryShardedTable() {
        when(cacheService.getLinkEntity("abc123")).thenReturn(null);
        when(cacheService.isNullCached("abc123")).thenReturn(false);
        when(cacheService.mightExist("abc123")).thenReturn(true);
        when(linkRouteMapper.selectById("abc123")).thenReturn(null);

        LinkEntity result = redirectService.getLinkEntity("abc123");

        assertNull(result);
        verify(linkMapper, never()).selectList(any(Wrapper.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<LinkEntity>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private LinkRouteEntity route() {
        LinkRouteEntity route = new LinkRouteEntity();
        route.setShortUri("abc123");
        route.setLinkId(123L);
        route.setGid("u_7");
        return route;
    }

    private LinkEntity link() {
        LinkEntity link = new LinkEntity();
        link.setId(123L);
        link.setGid("u_7");
        link.setShortUri("abc123");
        link.setOriginUrl("https://example.com");
        link.setEnableStatus(1);
        link.setDelFlag(0);
        link.setRedirectType(0);
        link.setExpireType(0);
        return link;
    }
}
