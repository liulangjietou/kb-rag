package io.kbrag.domain.entity;

import io.kbrag.domain.enums.PublishStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档治理的时间边界不依赖索引是否已经同步。 */
class DocumentAvailabilityTest {
    @Test
    void shouldIncludeEffectiveBoundaryAndExcludeExpiryBoundary() {
        var now = LocalDateTime.of(2026, 9, 11, 12, 0);
        var document = new Document();
        assertTrue(document.availableForRetrievalAt(now));
        document.setEffectiveAt(now.plusSeconds(1));
        assertFalse(document.availableForRetrievalAt(now));
        document.setEffectiveAt(now);
        assertTrue(document.availableForRetrievalAt(now));
        document.setExpiresAt(now);
        assertFalse(document.availableForRetrievalAt(now));
        document.setExpiresAt(now.plusSeconds(1));
        assertTrue(document.availableForRetrievalAt(now));
    }

    @Test
    void shouldRefuseUnpublishedTrashedAndDeletedDocuments() {
        var document = new Document();
        var now = LocalDateTime.now();
        for (var status : PublishStatus.values()) {
            document.setPublishStatus(status);
            if (status == PublishStatus.PUBLISHED) assertTrue(document.availableForRetrievalAt(now));
            else assertFalse(document.availableForRetrievalAt(now));
        }
        document.setPublishStatus(PublishStatus.PUBLISHED);
        document.setTrashed(1);
        assertFalse(document.availableForRetrievalAt(now));
        document.setTrashed(0);
        document.setDeleted(1);
        assertFalse(document.availableForRetrievalAt(now));
    }
}
