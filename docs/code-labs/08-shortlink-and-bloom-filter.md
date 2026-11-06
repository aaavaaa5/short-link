# 短链生成与布隆过滤器完整实现

## 0. 实现目标

```text
原始 URL -> 生成短码 -> 查重 -> 写 Bloom -> 插入数据库
访问短码 -> 查缓存 -> Bloom 判不存在 -> 查数据库
```

---

## 1. Maven 依赖

```xml
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
</dependency>

<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-all</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>
        spring-boot-starter-data-redis
    </artifactId>
</dependency>
```

---

## 2. 短码生成完整代码

```java
@Component
public class ShortLinkGenerator {

    private static final char[] CHARS = {
        '1','2','3','4','5','6','7','8','9',
        'A','B','C','D','E','F','G','H','J','K',
        'L','M','N','P','Q','R','S','T','U','V',
        'W','X','Y','Z','a','b','c','d','e','f',
        'g','h','i','j','k','m','n','o','p','q',
        'r','s','t','u','v','w','x','y','z'
    };

    private static final HashFunction HASH =
            Hashing.murmur3_32(0);

    public String generate(String url) {
        String seed = url
                + System.nanoTime()
                + RandomUtil.randomString(8);

        int hash = HASH.hashString(
                seed, StandardCharsets.UTF_8).asInt();

        long positive = Integer.toUnsignedLong(hash);
        return encode(positive, 6);
    }

    private String encode(long value, int length) {
        StringBuilder builder = new StringBuilder();

        do {
            int index = (int) (value % CHARS.length);
            builder.append(CHARS[index]);
            value /= CHARS.length;
        } while (value > 0);

        while (builder.length() < length) {
            builder.append('w');
        }

        return builder.reverse()
                .substring(0, length);
    }
}
```

流程：

```text
URL + 时间 + 随机数
  -> MurmurHash3
  -> 无符号数字
  -> 58 进制编码
  -> 6 位短码
```

---

## 3. Bloom 配置

```yaml
shortlink:
  bloom-filter:
    size: 33554432
    hash-count: 3
    salt: default
    key: shortlink:bf:short_uri
```

`33554432` 位：

```text
约 4 MiB
```

---

## 4. Bloom 配置类和 Helper

```java
@Configuration
public class BloomConfig {

    @Value("${shortlink.bloom-filter.size}")
    private int size;

    @Value("${shortlink.bloom-filter.hash-count}")
    private int hashCount;

    @Value("${shortlink.bloom-filter.salt}")
    private String salt;

    @Value("${shortlink.bloom-filter.key}")
    private String key;

    @Bean
    public BloomFilterHelper bloomFilterHelper(
            StringRedisTemplate redisTemplate) {
        return new BloomFilterHelper(
                redisTemplate,
                size,
                hashCount,
                salt,
                key,
                Hashing.murmur3_32(0));
    }
}
```

Helper：

```java
public class BloomFilterHelper {

    private final StringRedisTemplate redisTemplate;
    private final int size;
    private final int hashCount;
    private final String salt;
    private final String key;
    private final HashFunction hashFunction;

    public BloomFilterHelper(
            StringRedisTemplate redisTemplate,
            int size,
            int hashCount,
            String salt,
            String key,
            HashFunction hashFunction) {
        this.redisTemplate = redisTemplate;
        this.size = size;
        this.hashCount = hashCount;
        this.salt = salt;
        this.key = key;
        this.hashFunction = hashFunction;
    }

    public void add(String value) {
        for (int position : positions(value)) {
            redisTemplate.opsForValue()
                    .setBit(key, position, true);
        }
    }

    public boolean mightContain(String value) {
        for (int position : positions(value)) {
            if (!Boolean.TRUE.equals(
                    redisTemplate.opsForValue()
                            .getBit(key, position))) {
                return false;
            }
        }
        return true;
    }

    private int[] positions(String value) {
        int h1 = hashFunction.hashString(
                value, StandardCharsets.UTF_8).asInt();
        int h2 = hashFunction.hashString(
                value + ":" + salt,
                StandardCharsets.UTF_8).asInt();

        int[] result = new int[hashCount];
        for (int i = 0; i < hashCount; i++) {
            long raw = h1 + (long) i * h2;
            result[i] = (int) Math.floorMod(raw, size);
        }
        return result;
    }
}
```

---

## 5. 创建短链完整代码

```java
@Service
public class LinkService {

    private final LinkMapper linkMapper;
    private final ShortLinkGenerator generator;
    private final RedissonClient redissonClient;
    private final BloomFilterHelper bloom;

    public LinkEntity create(
            String originUrl,
            Long userId) throws InterruptedException {

        for (int retry = 0; retry < 3; retry++) {
            String candidate = generator.generate(originUrl);
            RLock lock = redissonClient.getLock(
                    "shortlink:lock:create:" + candidate);

            if (!lock.tryLock(1, 5, TimeUnit.SECONDS)) {
                continue;
            }

            try {
                if (exists(candidate)) {
                    continue;
                }

                LinkEntity entity = new LinkEntity();
                entity.setOriginUrl(originUrl);
                entity.setShortUri(candidate);
                entity.setGid("u_" + userId);
                entity.setCreateUserId(userId);
                entity.setEnableStatus(1);

                // 先写 Bloom，避免数据库有但过滤器没有
                bloom.add(candidate);
                linkMapper.insert(entity);
                return entity;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        throw new IllegalStateException("短码生成失败");
    }
}
```

---

## 6. 查询短链

```java
public LinkEntity get(String shortUri) {
    if (!bloom.mightContain(shortUri)) {
        return null;
    }

    LambdaQueryWrapper<LinkEntity> wrapper =
            Wrappers.lambdaQuery();
    wrapper.eq(
            LinkEntity::getShortUri, shortUri);

    List<LinkEntity> list =
            linkMapper.selectList(wrapper);
    return list.isEmpty() ? null : list.get(0);
}
```

Bloom 规则：

```text
false -> 一定不存在
true  -> 可能存在，继续查数据库
```

---

## 7. 完整跳转 Controller

```java
@Controller
public class RedirectController {

    @GetMapping("/{shortUri}")
    public String redirect(
            @PathVariable String shortUri,
            HttpServletResponse response,
            Model model) {

        LinkEntity link = linkService.get(shortUri);

        if (link == null) {
            response.setStatus(404);
            model.addAttribute("message", "短链不存在");
            return "error/404";
        }

        if (link.getEnableStatus() == 0) {
            response.setStatus(403);
            return "error/403";
        }

        return "redirect:" + link.getOriginUrl();
    }
}
```

`redirect:`：

```text
Spring MVC 创建 RedirectView
设置 302
设置 Location
```

---

## 8. 验证 Bloom

写入：

```bash
redis-cli SETBIT shortlink:bf:short_uri 123 1
redis-cli GETBIT shortlink:bf:short_uri 123
```

Java 写入后观察：

```bash
redis-cli STRLEN shortlink:bf:short_uri
```

测试不存在短码：

```bash
curl -i http://localhost:9000/s/not-exist
```

如果 Bloom 判断不存在，不会查询数据库。

---

## 9. 从零实现顺序

```text
1. 写短码生成器
2. 写 Bloom Helper
3. 创建 Bloom Bean
4. 创建短链时写 Bloom
5. 插入数据库
6. 跳转时先查 Bloom
7. Bloom 为 true 再查数据库
8. 加缓存和空值缓存
9. 启动时回填历史短码
```

---

## 10. 本篇自检

```text
[ ] 能生成 6 位短码
[ ] 能解释为什么加入时间和随机数
[ ] 能写 SETBIT/GETBIT
[ ] 能写双哈希
[ ] 能解释 Bloom 误判
[ ] 能解释为什么先写 Bloom 再插库
[ ] 能写 301/302 跳转
```
