package io.kbrag.api.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.mapper.AuthSessionMapper;
import io.kbrag.domain.port.PrincipalCache;
import io.kbrag.infrastructure.auth.LocalPrincipalCache;
import io.kbrag.infrastructure.auth.MysqlSaTokenDao;
import io.kbrag.infrastructure.auth.RedisPrincipalCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * 控制台会话与权限缓存的装配：存储介质二选一，以及会话时长的单一事实来源。
 *
 * <p>会话与权限缓存共用 {@code kb.cache.provider} 一个开关，因为它们必须同进同退：只共享其中一个，
 * 会得到"登录态跨节点一致、授权判据不一致"这种错位，比两者都不共享更难察觉。
 *
 * <p><b>为什么两条存储分支都显式声明，而不是让框架自动装配其一。</b> Sa-Token 的
 * {@link SaTokenDaoForRedisTemplate} 是一个无条件生效的自动装配类——它没有
 * {@code @ConditionalOnMissingBean}，只要 jar 在 classpath 上就会注册。而框架侧是用
 * {@code @Autowired(required = false)} 单值注入 {@link SaTokenDao} 的，容器里同时存在两个实现会直接
 * 启动失败。所以 application.yml 把它从自动装配里排除，改由这里按 {@code kb.cache.provider} 二选一：
 * 装配路径唯一，而且在同一个类里一眼能看全。
 *
 * <p><b>为什么 Redis 连接工厂也自己声明。</b> 若沿用 Spring Boot 的 {@code RedisAutoConfiguration}，
 * 那么即使在 {@code local} 模式下容器里也会存在一个 {@code RedisConnectionFactory}，actuator 的 Redis
 * 健康检查随之生效，去连一个这套部署根本没有的 Redis，健康端点就永久 DOWN。排除掉它、只在 {@code redis}
 * 分支里建连接工厂，单实例部署就完全看不见 Redis 的任何痕迹——这正是需求文档 §5 承诺的"Redis 是可选
 * 依赖"。配置项仍然是标准的 {@code spring.data.redis.*}，绑定复用 Boot 自己的 {@link RedisProperties}，
 * 不另发明一套。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RedisProperties.class)
public class ConsoleSessionConfig {

    private static final int SECONDS_PER_HOUR = 3600;

    private final cn.dev33.satoken.config.SaTokenConfig saTokenConfig;

    private final KbProperties properties;

    /**
     * 把会话时长对齐到 {@code kb.auth.token-ttl-hours}，让它继续做唯一事实来源。
     *
     * <p>Sa-Token 自带 {@code sa-token.timeout}，而本项目在引入它之前就已经有 {@code AUTH_TOKEN_TTL_HOURS}
     * 这个环境变量在管会话时长。两个配置并存的后果不是"多一种写法"，而是运维改了原来那个却不生效——这是
     * 静默的行为回归。因此这里在框架读取配置之后、任何请求进来之前，用项目自己的配置覆盖框架的默认值：
     * {@code sa-token.*} 下的其余开关照常生效，唯独时长由项目这一侧说了算。
     */
    @PostConstruct
    public void alignSessionTimeout() {
        long timeoutSeconds = (long) properties.getAuth().getTokenTtlHours() * SECONDS_PER_HOUR;
        saTokenConfig.setTimeout(timeoutSeconds);
        log.info("console session timeout aligned, hours={}, seconds={}",
                properties.getAuth().getTokenTtlHours(), timeoutSeconds);
    }

    /**
     * 单实例部署的会话存储：写进 MySQL，进程重启后会话依然有效。
     *
     * @param authSessionMapper 会话表数据访问
     * @return MySQL 存储实现
     */
    @Bean
    @ConditionalOnProperty(name = "kb.cache.provider",
            havingValue = KbProperties.Cache.PROVIDER_LOCAL, matchIfMissing = true)
    public MysqlSaTokenDao mysqlSaTokenDao(AuthSessionMapper authSessionMapper) {
        log.info("console session store initialized, provider={}", KbProperties.Cache.PROVIDER_LOCAL);
        return new MysqlSaTokenDao(authSessionMapper);
    }

    /**
     * 单实例部署的权限缓存：进程内的 Map。
     *
     * <p>与会话存储共用同一个开关，不是图省事——两者分开配就会出现"登录态跨节点共享、授权判据不共享"
     * 这种错位，而它比两者都不共享更难察觉：登录一切正常，只有某个刚被降权的账号在某个节点上还是管理员。
     *
     * @return 进程内实现
     */
    @Bean
    @ConditionalOnProperty(name = "kb.cache.provider",
            havingValue = KbProperties.Cache.PROVIDER_LOCAL, matchIfMissing = true)
    public PrincipalCache localPrincipalCache() {
        log.info("permission cache initialized, provider={}", KbProperties.Cache.PROVIDER_LOCAL);
        return new LocalPrincipalCache();
    }

    /**
     * 多实例部署的权限缓存：各节点共享一份，角色变更在下一次请求即刻可见。
     *
     * @param stringRedisTemplate 本类声明的字符串模板
     * @return 共享实现
     */
    @Bean
    @ConditionalOnProperty(name = "kb.cache.provider", havingValue = KbProperties.Cache.PROVIDER_REDIS)
    public PrincipalCache redisPrincipalCache(StringRedisTemplate stringRedisTemplate) {
        log.info("permission cache initialized, provider={}", KbProperties.Cache.PROVIDER_REDIS);
        return new RedisPrincipalCache(stringRedisTemplate);
    }

    /**
     * 多实例部署的会话存储：交给 Sa-Token 官方 Redis 适配，各节点看到同一份登录态。
     *
     * @param connectionFactory 本类声明的 Redis 连接工厂
     * @return Redis 存储实现
     */
    @Bean
    @ConditionalOnProperty(name = "kb.cache.provider", havingValue = KbProperties.Cache.PROVIDER_REDIS)
    public SaTokenDao redisSaTokenDao(RedisConnectionFactory connectionFactory) {
        SaTokenDaoForRedisTemplate dao = new SaTokenDaoForRedisTemplate();
        dao.init(connectionFactory);
        log.info("console session store initialized, provider={}", KbProperties.Cache.PROVIDER_REDIS);
        return dao;
    }

    /**
     * Redis 连接工厂，只在 {@code kb.cache.provider=redis} 时存在。
     *
     * @param redisProperties 标准的 {@code spring.data.redis.*} 绑定
     * @return Lettuce 连接工厂
     */
    @Bean
    @ConditionalOnProperty(name = "kb.cache.provider", havingValue = KbProperties.Cache.PROVIDER_REDIS)
    public RedisConnectionFactory redisConnectionFactory(RedisProperties redisProperties) {
        return buildConnectionFactory(redisProperties);
    }

    /**
     * 补齐 Spring Data Redis 生态默认提供的两个模板 bean。
     *
     * <p><b>为什么必须自己给。</b> 排除 {@code RedisAutoConfiguration} 换来了"local 模式下容器里没有
     * Redis 连接工厂"，但也一并拿走了它顺带定义的 {@code redisTemplate} / {@code stringRedisTemplate}。
     * 生态里其它组件是按"这两个 bean 一定在"来写的——{@code RedisRepositoriesAutoConfiguration} 就按
     * 名字找 {@code redisTemplate}，而它的生效条件是"容器里有 RedisConnectionFactory"，于是它在
     * local 下不生效、在 redis 下必然生效，启动直接失败在一个只有切到 redis 才会踩到的洞上。
     *
     * <p>结论是：排除一个自动装配，就要把它承担的契约接过来，而不是只补自己用得到的那部分。这里按
     * {@code RedisAutoConfiguration} 的原样补齐，两个 bean 都标 {@code @ConditionalOnMissingBean}，
     * 将来若有人把排除清单改回去也不会冲突。
     *
     * @param connectionFactory 本类声明的连接工厂
     * @return 通用 Redis 模板
     */
    @Bean
    @ConditionalOnProperty(name = "kb.cache.provider", havingValue = KbProperties.Cache.PROVIDER_REDIS)
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<Object, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<Object, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    /**
     * 字符串形态的 Redis 模板，同样是被排除的自动装配原本会提供的。
     *
     * @param connectionFactory 本类声明的连接工厂
     * @return 字符串 Redis 模板
     */
    @Bean
    @ConditionalOnProperty(name = "kb.cache.provider", havingValue = KbProperties.Cache.PROVIDER_REDIS)
    @ConditionalOnMissingBean(name = "stringRedisTemplate")
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    private RedisConnectionFactory buildConnectionFactory(RedisProperties redisProperties) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(redisProperties.getHost(), redisProperties.getPort());
        standalone.setDatabase(redisProperties.getDatabase());
        if (StringUtils.hasText(redisProperties.getUsername())) {
            standalone.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            standalone.setPassword(redisProperties.getPassword());
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder();
        if (redisProperties.getTimeout() != null) {
            builder.commandTimeout(redisProperties.getTimeout());
        }
        if (redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled()) {
            builder.useSsl();
        }
        return new LettuceConnectionFactory(standalone, builder.build());
    }
}
