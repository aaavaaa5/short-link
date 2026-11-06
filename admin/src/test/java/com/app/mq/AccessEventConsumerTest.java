package com.app.mq;

import com.app.entity.AccessLogEntity;
import com.app.mapper.AccessLogMapper;
import com.app.util.GeoIpUtil;
import com.app.util.UserAgentUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessEventConsumerTest {

    private static final String STREAM_KEY = "shortlink:access:stream";
    private static final String GROUP = "admin-consumer-group";
    private static final Duration MIN_IDLE = Duration.ofSeconds(30);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Mock
    private AccessLogMapper accessLogMapper;

    @Mock
    private UserAgentUtil userAgentUtil;

    @Mock
    private GeoIpUtil geoIpUtil;

    @Mock
    private RedisConnection redisConnection;

    @Mock
    private RedisStreamCommands streamCommands;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        lenient().when(redisTemplate.getStringSerializer()).thenReturn(StringRedisSerializer.UTF_8);
        lenient().when(redisConnection.streamCommands()).thenReturn(streamCommands);
        lenient().when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(redisConnection);
        });
        lenient().when(streamOperations.pending(
                eq(STREAM_KEY), eq(GROUP), any(Range.class), eq(100L)))
                .thenReturn(emptyPending());
        lenient().when(streamOperations.acknowledge(
                anyString(), anyString(), anyString())).thenReturn(1L);
    }

    @Test
    void doesNotAcknowledgeWhenDatabaseWriteFails() {
        MapRecord<String, Object, Object> record = record("1-0");
        when(streamOperations.pending(eq(STREAM_KEY), eq(GROUP), any(Range.class), eq(100L)))
                .thenReturn(emptyPending());
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class))).thenReturn(List.of(record));
        when(accessLogMapper.insert(any(AccessLogEntity.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        consumer().consume();

        verify(streamOperations, never())
                .acknowledge(eq(STREAM_KEY), eq(GROUP), eq(record.getId().getValue()));
    }

    @Test
    void createsGroupFromZeroWithMkStreamOnlyOnce() {
        AccessEventConsumer consumer = consumer();
        consumer.consume();
        consumer.consume();

        verify(streamCommands, times(1)).xGroupCreate(
                any(byte[].class), eq(GROUP), eq(ReadOffset.from("0")), eq(true));
    }

    @Test
    void onlyClaimsPendingMessagesThatExceedIdleThreshold() {
        MapRecord<String, Object, Object> oldRecord = record("1-0");
        MapRecord<String, Object, Object> newRecord = record("2-0");
        PendingMessage oldPending = new PendingMessage(
                oldRecord.getId(), Consumer.from(GROUP, "dead-consumer"), Duration.ofSeconds(60), 1);
        PendingMessage newPending = new PendingMessage(
                newRecord.getId(), Consumer.from(GROUP, "live-consumer"), Duration.ofSeconds(5), 1);

        when(streamOperations.pending(eq(STREAM_KEY), eq(GROUP), any(Range.class), eq(100L)))
                .thenReturn(new PendingMessages(GROUP, List.of(oldPending, newPending)));
        when(streamOperations.claim(eq(STREAM_KEY), eq(GROUP), anyString(), eq(MIN_IDLE),
                eq(oldRecord.getId()))).thenReturn(List.of(oldRecord));
        when(streamOperations.range(eq(STREAM_KEY), any(Range.class)))
                .thenReturn(List.of(oldRecord));

        consumer().consume();

        verify(accessLogMapper, times(1)).insert(any(AccessLogEntity.class));
        verify(streamOperations, never()).claim(
                eq(STREAM_KEY), eq(GROUP), anyString(), eq(MIN_IDLE), eq(newRecord.getId()));
    }

    @Test
    void createsUniqueConsumerNamePerInstance() {
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class))).thenReturn(Collections.emptyList());

        consumer().consume();
        consumer().consume();

        ArgumentCaptor<Consumer> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(streamOperations, times(2)).read(
                consumerCaptor.capture(), any(StreamReadOptions.class), any(StreamOffset.class));
        assertNotEquals(
                consumerCaptor.getAllValues().get(0).getName(),
                consumerCaptor.getAllValues().get(1).getName());
    }

    @Test
    void drainsMoreThanOneBatchPerScheduledRun() {
        MapRecord<String, Object, Object> record = record("1-0");
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class)))
                .thenReturn(Collections.nCopies(100, record))
                .thenReturn(Collections.emptyList());

        consumer().consume();

        verify(streamOperations, times(2)).read(
                any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));
    }

    @Test
    void duplicateMessageIsTreatedAsSuccessfullyProcessed() {
        MapRecord<String, Object, Object> record = record("1-0");
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class))).thenReturn(List.of(record));
        when(accessLogMapper.insert(any(AccessLogEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate message id"));

        consumer().consume();

        verify(streamOperations).acknowledge(
                STREAM_KEY, GROUP, record.getId().getValue());
    }

    @Test
    void invalidAccessTimeFallsBackWithoutLosingMessage() {
        MapRecord<String, Object, Object> record = record("1-0", "not-a-timestamp");
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class))).thenReturn(List.of(record));

        consumer().consume();

        ArgumentCaptor<AccessLogEntity> entityCaptor = ArgumentCaptor.forClass(AccessLogEntity.class);
        verify(accessLogMapper).insert(entityCaptor.capture());
        verify(streamOperations).acknowledge(
                STREAM_KEY, GROUP, record.getId().getValue());
        assertNotNull(entityCaptor.getValue().getAccessTime());
    }

    @Test
    void cleansPendingMessageThatWasTrimmedFromStream() {
        MapRecord<String, Object, Object> record = record("1-0");
        PendingMessage pending = new PendingMessage(
                record.getId(), Consumer.from(GROUP, "dead-consumer"), Duration.ofSeconds(60), 1);
        when(streamOperations.pending(eq(STREAM_KEY), eq(GROUP), any(Range.class), eq(100L)))
                .thenReturn(new PendingMessages(GROUP, List.of(pending)));
        when(streamOperations.claim(eq(STREAM_KEY), eq(GROUP), anyString(), eq(MIN_IDLE),
                eq(record.getId()))).thenReturn(Collections.emptyList());
        when(streamOperations.range(eq(STREAM_KEY), any(Range.class)))
                .thenReturn(Collections.emptyList());

        consumer().consume();

        verify(streamOperations).acknowledge(
                STREAM_KEY, GROUP, record.getId().getValue());
    }

    @Test
    void reusesUserAgentAndGeoIpLookupsForRepeatedValues() {
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class))).thenReturn(List.of(record("1-0"), record("2-0")));

        consumer().consume();

        verify(userAgentUtil, times(1)).parseBrowser("Mozilla/5.0");
        verify(userAgentUtil, times(1)).parseOs("Mozilla/5.0");
        verify(userAgentUtil, times(1)).parseDevice("Mozilla/5.0");
        verify(geoIpUtil, times(1)).parseCountry("127.0.0.1");
        verify(geoIpUtil, times(1)).parseProvince("127.0.0.1");
        verify(geoIpUtil, times(1)).parseCity("127.0.0.1");
    }

    private AccessEventConsumer consumer() {
        AccessEventConsumer consumer = new AccessEventConsumer();
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "accessLogMapper", accessLogMapper);
        ReflectionTestUtils.setField(consumer, "userAgentUtil", userAgentUtil);
        ReflectionTestUtils.setField(consumer, "geoIpUtil", geoIpUtil);
        return consumer;
    }

    private PendingMessages emptyPending() {
        return new PendingMessages(GROUP, Collections.emptyList());
    }

    private MapRecord<String, Object, Object> record(String id) {
        return record(id, "1700000000000");
    }

    private MapRecord<String, Object, Object> record(String id, String accessTime) {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("link_id", "1");
        fields.put("link_gid", "u_1");
        fields.put("short_uri", "abc123");
        fields.put("visitor_id", "visitor-1");
        fields.put("visitor_ip", "127.0.0.1");
        fields.put("user_agent", "Mozilla/5.0");
        fields.put("referer", "https://example.com");
        fields.put("access_time", accessTime);
        return MapRecord.create(STREAM_KEY, fields).withId(RecordId.of(id));
    }
}
