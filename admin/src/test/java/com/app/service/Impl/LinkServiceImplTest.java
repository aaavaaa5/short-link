package com.app.service.Impl;

import com.app.common.bloom.BloomFilterHelper;
import com.app.dto.req.LinkCreateReqDTO;
import com.app.dto.resp.LinkVO;
import com.app.entity.LinkEntity;
import com.app.entity.LinkRouteEntity;
import com.app.entity.Result;
import com.app.mapper.LinkMapper;
import com.app.mapper.LinkRouteMapper;
import com.app.util.ShortLinkGenerator;
import com.app.util.UserContext;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkServiceImplTest {

    @Mock
    private LinkMapper linkMapper;

    @Mock
    private LinkRouteMapper linkRouteMapper;

    @Mock
    private ShortLinkGenerator shortLinkGenerator;

    @Mock
    private IdentifierGenerator identifierGenerator;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private BloomFilterHelper bloomFilterHelper;

    private LinkServiceImpl linkService;

    @BeforeEach
    void setUp() {
        linkService = new LinkServiceImpl();
        ReflectionTestUtils.setField(linkService, "linkMapper", linkMapper);
        ReflectionTestUtils.setField(linkService, "linkRouteMapper", linkRouteMapper);
        ReflectionTestUtils.setField(linkService, "shortLinkGenerator", shortLinkGenerator);
        ReflectionTestUtils.setField(linkService, "identifierGenerator", identifierGenerator);
        ReflectionTestUtils.setField(linkService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(linkService, "bloomFilterHelper", bloomFilterHelper);
        ReflectionTestUtils.setField(linkService, "domain", "http://localhost:9000/s");
        UserContext.set(7L, "tester", "Tester");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createReservesRouteBeforeWritingLink() {
        when(identifierGenerator.nextId(any())).thenReturn(123456789012345L);
        when(shortLinkGenerator.generate("https://example.com")).thenReturn("abc123");
        when(linkRouteMapper.insert(any(LinkRouteEntity.class))).thenReturn(1);
        when(linkMapper.insert(any(LinkEntity.class))).thenReturn(1);

        Result<LinkVO> result = linkService.create(createRequest());

        assertNotNull(result.getData());
        assertEquals("abc123", result.getData().getShortUri());
        ArgumentCaptor<LinkRouteEntity> routeCaptor = ArgumentCaptor.forClass(LinkRouteEntity.class);
        verify(linkRouteMapper).insert(routeCaptor.capture());
        assertEquals(123456789012345L, routeCaptor.getValue().getLinkId());
        assertEquals("u_7", routeCaptor.getValue().getGid());
        verify(linkMapper).insert(any(LinkEntity.class));
    }

    @Test
    void duplicateRouteReservationRetriesWithAnotherShortCode() {
        when(identifierGenerator.nextId(any())).thenReturn(123L);
        when(shortLinkGenerator.generate("https://example.com"))
                .thenReturn("abc123", "def456");
        when(linkRouteMapper.insert(any(LinkRouteEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenReturn(1);
        when(linkMapper.insert(any(LinkEntity.class))).thenReturn(1);

        Result<LinkVO> result = linkService.create(createRequest());

        assertEquals("def456", result.getData().getShortUri());
        verify(linkRouteMapper, org.mockito.Mockito.times(2))
                .insert(any(LinkRouteEntity.class));
    }

    @Test
    void failedLinkInsertReleasesReservedRoute() {
        when(identifierGenerator.nextId(any())).thenReturn(123L);
        when(shortLinkGenerator.generate("https://example.com")).thenReturn("abc123");
        when(linkRouteMapper.insert(any(LinkRouteEntity.class))).thenReturn(1);
        when(linkMapper.insert(any(LinkEntity.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> linkService.create(createRequest()));

        verify(linkRouteMapper).deleteById("abc123");
    }

    @Test
    void distributedIdsAreSerializedAsStrings() throws Exception {
        LinkVO linkVO = new LinkVO();
        linkVO.setId(123456789012345L);

        String json = new ObjectMapper().writeValueAsString(linkVO);

        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"id\":\"123456789012345\""));
    }

    private LinkCreateReqDTO createRequest() {
        LinkCreateReqDTO req = new LinkCreateReqDTO();
        req.setOriginUrl("https://example.com");
        return req;
    }
}
