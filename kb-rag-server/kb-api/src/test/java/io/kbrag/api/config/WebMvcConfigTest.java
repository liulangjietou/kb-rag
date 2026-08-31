package io.kbrag.api.config;

import io.kbrag.api.filter.AuthInterceptor;
import io.kbrag.api.filter.PermissionInterceptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 固化登录前置接口必须绕过会话拦截器的公开路径契约。
 *
 * @author owlzhangfq@gmail.com
 */
class WebMvcConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeCaptchaChallengeAndVerifyBeforeLogin() throws Exception {
        WebMvcConfig config = new WebMvcConfig(mock(AuthInterceptor.class), mock(PermissionInterceptor.class));
        Field publicPathsField = WebMvcConfig.class.getDeclaredField("PUBLIC_PATHS");
        publicPathsField.setAccessible(true);
        List<String> publicPaths = (List<String>) publicPathsField.get(config);

        assertTrue(publicPaths.contains("/api/v1/auth/captcha/challenge"));
        assertTrue(publicPaths.contains("/api/v1/auth/captcha/verify"));
    }
}
