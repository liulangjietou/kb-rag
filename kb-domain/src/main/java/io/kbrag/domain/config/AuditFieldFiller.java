package io.kbrag.domain.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Fills the audit, optimistic lock and soft delete columns shared by every business table, so no
 * service has to remember them.
 */
@Component
public class AuditFieldFiller implements MetaObjectHandler {

    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String FIELD_LOCK_VERSION = "lockVersion";
    private static final String FIELD_DELETED = "deleted";

    private static final int INITIAL_LOCK_VERSION = 0;
    private static final int NOT_DELETED = 0;

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, FIELD_CREATED_AT, LocalDateTime.class, now);
        strictInsertFill(metaObject, FIELD_UPDATED_AT, LocalDateTime.class, now);
        strictInsertFill(metaObject, FIELD_LOCK_VERSION, Integer.class, INITIAL_LOCK_VERSION);
        strictInsertFill(metaObject, FIELD_DELETED, Integer.class, NOT_DELETED);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, FIELD_UPDATED_AT, LocalDateTime.class, LocalDateTime.now());
    }
}
