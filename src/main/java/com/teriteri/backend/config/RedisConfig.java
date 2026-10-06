package com.teriteri.backend.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.lang.reflect.Method;

@Configuration
@EnableCaching
public class RedisConfig extends CachingConfigurerSupport {

    /**
     * 缓存键生成器，配合 Spring Cache 注解使用
     * 在这个项目中用不到，我更倾向自己手动命名，如 user:1:age
     *
     * @return
     */
    @Bean
    @SuppressWarnings("all")   // 压制编译器警告  all：所有
    public KeyGenerator keyGenerator() {
        return new KeyGenerator() {
            @Override
            public Object generate(Object target, Method method, Object... params) {
                StringBuilder sb = new StringBuilder();
                sb.append(target.getClass().getName());
                sb.append(method.getName());
                for (Object obj : params) {
                    sb.append(obj.toString());
                }
                return sb.toString();
            }
        };
    }

    /**
     * Redis 缓存管理器
     *
     * @param connectionFactory
     * @return
     */
    @Bean
    @SuppressWarnings("all")
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheManager redisCacheManager = RedisCacheManager.builder(connectionFactory).build();
        return redisCacheManager;
    }

    /**
     * 编写自己的 redisTemplate，用于与 Redis 进行交互，配置了json格式存储，序列化与反序列化
     *
     * @param redisConnectionFactory
     * @return
     */
    @Bean
    @SuppressWarnings("all")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        /*
           下面两句代码是什么意思？
           将跟 Redis建立连接，看成 打电话， RedisTemplate就是 一款手机，而 redisConnectionFactory 就是 sim卡
           redisConnectionFactory 里面有 Redis的 IP地址、端口号、密码、数据库等信息，这些信息是从 .yml 来的
           Spring Boot 会创建并注入到这个对象中，相当于 激活 sim卡
         */
        // 为了方便自己开发，一般直接使用 <String, Object>
        RedisTemplate<String, Object> template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(redisConnectionFactory);

        // 序列化配置
        Jackson2JsonRedisSerializer jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer(Object.class); // 用 json进行序列化，对象为 Object
        ObjectMapper om = new ObjectMapper();     // ObjectMapper 是 Jackson 库的核心类，真正负责 JSON 转换
        // 对所有类型的成员（字段、getter、setter、构造器），无论其可见性是 public/private/protected/default等，都允许 Jackson 直接访问
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 在 JSON 里额外保存“这个对象原来是什么 Java 类”
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
        // 把配置好的 ObjectMapper 塞给序列化器
        jackson2JsonRedisSerializer.setObjectMapper(om);
        // String的序列化
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        // key采用了String的序列化方式
        template.setKeySerializer(stringRedisSerializer);
        // hash的 field 采用String的序列化方式
        template.setHashKeySerializer(stringRedisSerializer);
        // value序列化方式采用jackson
        template.setValueSerializer(jackson2JsonRedisSerializer);
        // hash的value序列化方式采用jackson
        template.setHashValueSerializer(jackson2JsonRedisSerializer);
        // 手动 new 出来的 RedisTemplate，需要调用它来检查配置是否完整，并完成 Bean的初始化
        template.afterPropertiesSet();

        return template;
    }



    /**
     *  1. 下面这段代码没有写的必要性，因为 当我们引入 spring-boot-starter-data-redis 依赖后，
     *      Spring Boot 的 RedisAutoConfiguration 就会自动创建两个 Bean：RedisTemplate、StringRedisTemplate
     *      而当我们自己声明定义了 对应的Bean，就会优先用我们的
     *  2. 为什么重写 RedisTempalte
     *      因为 序列化默认是 JDK序列化，不是我们需要的；
     *      而 StringRedisTemplate默认是 String序列化，也就是 StringRedisSerializer，是我们需要的
     */
    @Bean
    @SuppressWarnings("all")
    //一个专门用于操作 Redis 字符串类型的模板，它是 RedisTemplate 的子类，只支持字符串数据的存储和检索
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate stringRedisTemplate = new StringRedisTemplate();
        stringRedisTemplate.setConnectionFactory(factory);
        return stringRedisTemplate;
    }
}