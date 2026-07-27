package io.kbrag.app.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * Registers entity metadata so a plain unit test can build a MyBatis-Plus lambda wrapper.
 *
 * <p>Why it is needed. {@code LambdaUpdateWrapper.set} resolves a method reference to a column name through a
 * cache the framework fills while it scans the mappers at startup. A unit test has no Spring context and
 * therefore no scan, so any production code path that builds such a wrapper - the ones that write {@code null}
 * into a column, which {@code updateById} skips by design - fails with "can not find lambda cache" long before
 * the assertion it was meant to exercise.
 *
 * <p>Registering the entity here is the smallest possible substitute for that scan: it populates the same cache
 * from the {@code @TableField} annotations the entity already declares, so the test sees the column names
 * production would use.
 *
 * @author owlzhangfq@gmail.com
 */
public final class MybatisLambdaCache {

    private MybatisLambdaCache() {
    }

    /**
     * Fills the lambda column cache of the given entities, idempotently.
     *
     * @param entities entity classes the test's wrappers address
     */
    public static void register(Class<?>... entities) {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : entities) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
