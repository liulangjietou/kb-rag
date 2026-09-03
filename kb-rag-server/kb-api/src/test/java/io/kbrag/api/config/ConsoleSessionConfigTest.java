package io.kbrag.api.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.mapper.AuthSessionMapper;
import io.kbrag.domain.port.PrincipalCache;
import io.kbrag.infrastructure.auth.LocalPrincipalCache;
import io.kbrag.infrastructure.auth.MysqlSaTokenDao;
import io.kbrag.infrastructure.auth.RedisPrincipalCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 覆盖控制台会话存储的装配开关。
 *
 * <p>这里验证的三件事都是"编译和单元测试都看不出来、只在启动时才会炸或者才会静默走错"的：
 * ①两种 provider 各自装配出哪个实现；②容器里**只能有一个** {@link SaTokenDao}——框架侧是
 * {@code @Autowired(required = false)} 单值注入，多一个就启动失败；③{@code local} 模式下容器里
 * **不能有** {@code RedisConnectionFactory}——有的话 actuator 的 Redis 健康探针就会注册，
 * 去连一个这套部署根本没有的 Redis，健康端点永久 DOWN。
 *
 * @author owlzhangfq@gmail.com
 */
class ConsoleSessionConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubBeans.class, ConsoleSessionConfig.class);

    @Test
    void shouldUseMysqlStoreByDefault() {
        // 不配 provider 就该走 local：默认部署形态不依赖 Redis 是需求文档 §5 的承诺。
        runner.run(context -> {
            assertThat(context).hasSingleBean(SaTokenDao.class);
            assertThat(context.getBean(SaTokenDao.class)).isInstanceOf(MysqlSaTokenDao.class);
        });
    }

    @Test
    void shouldUseMysqlStoreWhenProviderIsLocal() {
        runner.withPropertyValues("kb.cache.provider=local").run(context -> {
            assertThat(context).hasSingleBean(SaTokenDao.class);
            assertThat(context.getBean(SaTokenDao.class)).isInstanceOf(MysqlSaTokenDao.class);
        });
    }

    @Test
    void shouldNotCreateARedisConnectionFactoryOnTheLocalPath() {
        // 这条是健康检查那个坑的回归测试：连接工厂在则探针在，探针在则每套单实例部署都报 DOWN。
        runner.withPropertyValues("kb.cache.provider=local")
                .run(context -> assertThat(context).doesNotHaveBean(RedisConnectionFactory.class));
    }

    @Test
    void shouldUseRedisStoreWhenProviderIsRedis() {
        runner.withPropertyValues(
                        "kb.cache.provider=redis",
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=6379")
                .run(context -> {
                    assertThat(context).hasSingleBean(SaTokenDao.class);
                    assertThat(context.getBean(SaTokenDao.class))
                            .isInstanceOf(SaTokenDaoForRedisTemplate.class);
                    // 连接工厂只是被建出来，Lettuce 是懒连接，这里并不会真去握手。
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                });
    }

    @Test
    void shouldStillExposeExactlyOneStoreWhenRedisAutoConfigurationIsPresent() {
        // application.yml 排除了 RedisAutoConfiguration；这里把它显式放回来，确认即便有人改了那份
        // 排除清单，local 路径也不会突然冒出第二个连接工厂来把健康检查带坏。
        runner.withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues("kb.cache.provider=local")
                .run(context -> {
                    assertThat(context).hasSingleBean(SaTokenDao.class);
                    assertThat(context.getBean(SaTokenDao.class)).isInstanceOf(MysqlSaTokenDao.class);
                });
    }

    @Test
    void shouldStartWithRedisRepositoriesAutoConfigurationPresent() {
        // 回归测试，对应一次真实的启动失败：
        //   "A component required a bean named 'redisTemplate' that could not be found."
        // RedisRepositoriesAutoConfiguration 的生效条件是"容器里有 RedisConnectionFactory"，因此它在
        // local 下不生效、一切到 redis 就必然生效，然后按名字去找 redisTemplate——而那个 bean 原本由
        // 被我们排除掉的 RedisAutoConfiguration 提供。排除一个自动装配就要接过它承担的契约，
        // 这条用例守的就是这件事。
        runner.withConfiguration(AutoConfigurations.of(RedisRepositoriesAutoConfiguration.class))
                // 这个自动装配要读 @EnableAutoConfiguration 的 base package 去扫 repository。真实应用由
                // @SpringBootApplication 提供，ApplicationContextRunner 里没有，得手工登记一个，
                // 否则它会先因为拿不到包名而失败，根本走不到我们要验的那一步。
                // 用本测试所在包：这里没有任何 Redis repository，扫描结果为空，不影响断言。
                .withInitializer(context -> AutoConfigurationPackages.register(
                        (BeanDefinitionRegistry) context.getBeanFactory(), getClass().getPackageName()))
                .withPropertyValues("kb.cache.provider=redis", "spring.data.redis.host=127.0.0.1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("redisTemplate");
                    assertThat(context).hasBean("stringRedisTemplate");
                });
    }

    @Test
    void shouldNotCreateRedisTemplatesOnTheLocalPath() {
        // 模板 bean 跟连接工厂同进同退：local 模式下一个 Redis 相关的 bean 都不该存在。
        runner.withPropertyValues("kb.cache.provider=local").run(context -> {
            assertThat(context).doesNotHaveBean("redisTemplate");
            assertThat(context).doesNotHaveBean("stringRedisTemplate");
        });
    }

    @Test
    void shouldKeepSessionStoreAndPermissionCacheOnTheSameSwitch() {
        // 会话与权限缓存必须同进同退。只切一半会得到"登录态跨节点一致、授权判据不一致"这种错位——
        // 登录一切正常，只有某个刚被降权的账号在某个节点上还是管理员，比两者都不共享更难察觉。
        runner.withPropertyValues("kb.cache.provider=local").run(context -> {
            assertThat(context).hasSingleBean(PrincipalCache.class);
            assertThat(context.getBean(PrincipalCache.class)).isInstanceOf(LocalPrincipalCache.class);
            assertThat(context.getBean(SaTokenDao.class)).isInstanceOf(MysqlSaTokenDao.class);
        });

        runner.withPropertyValues("kb.cache.provider=redis", "spring.data.redis.host=127.0.0.1")
                .run(context -> {
                    assertThat(context).hasSingleBean(PrincipalCache.class);
                    assertThat(context.getBean(PrincipalCache.class))
                            .isInstanceOf(RedisPrincipalCache.class);
                    assertThat(context.getBean(SaTokenDao.class))
                            .isInstanceOf(SaTokenDaoForRedisTemplate.class);
                });
    }

    @Test
    void shouldDefaultThePermissionCacheToTheInProcessImplementation() {
        runner.run(context -> assertThat(context.getBean(PrincipalCache.class))
                .isInstanceOf(LocalPrincipalCache.class));
    }

    @Test
    void shouldAlignSessionTimeoutWithTheProjectConfiguration() {
        // Sa-Token 自带 sa-token.timeout，项目自带 kb.auth.token-ttl-hours。两个都能改会让运维改了
        // 后者却不生效，因此启动时由后者覆盖前者；这里确认覆盖真的发生了，且换算成秒。
        runner.withPropertyValues("kb.auth.token-ttl-hours=8").run(context -> {
            cn.dev33.satoken.config.SaTokenConfig saTokenConfig =
                    context.getBean(cn.dev33.satoken.config.SaTokenConfig.class);
            assertThat(saTokenConfig.getTimeout()).isEqualTo(8 * 3600L);
        });
    }

    /**
     * 被测配置类的协作者。{@link KbProperties} 手工绑定，避免为一个开关拉起整套配置装配。
     */
    @Configuration(proxyBeanMethods = false)
    static class StubBeans {

        @Bean
        cn.dev33.satoken.config.SaTokenConfig saTokenConfig() {
            return new cn.dev33.satoken.config.SaTokenConfig();
        }

        @Bean
        KbProperties kbProperties(org.springframework.core.env.Environment environment) {
            KbProperties properties = new KbProperties();
            String hours = environment.getProperty("kb.auth.token-ttl-hours");
            if (hours != null) {
                properties.getAuth().setTokenTtlHours(Integer.parseInt(hours));
            }
            return properties;
        }

        @Bean
        AuthSessionMapper authSessionMapper() {
            return mock(AuthSessionMapper.class);
        }
    }
}
