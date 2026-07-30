package io.kbrag.domain.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus plugin chain.
 *
 * <p>The tenant line interceptor fences the root aggregate tables per caller and must run before the
 * pagination one, so the tenant clause also lands in the generated count statement. Pagination is
 * required by the document and chunk listings; the optimistic locker activates the
 * {@code lock_version} column every business table carries.
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * Registers the tenant line, pagination and optimistic lock inner interceptors, in that order.
     *
     * @param tenantLineHandler per request tenant fence of the root aggregate tables
     * @return configured interceptor chain
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(KbTenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
