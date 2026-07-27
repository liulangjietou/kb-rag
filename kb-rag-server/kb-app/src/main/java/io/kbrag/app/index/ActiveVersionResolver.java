package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * The current version visibility set of a knowledge base: the active version of each of its documents,
 * requirement section 4.4 "version visibility set".
 *
 * <p><b>Why it is cached.</b> The set is the mandatory engine side filter of every recall route, so it is
 * read at least once per knowledge base per search, and it is derived from one row per document. At a hundred
 * thousand chunks that query stops being free while its answer only changes when an operator switches an
 * active version - a rate measured in switches per day against searches per second.
 *
 * <p><b>Why invalidation and not only a short expiry.</b> An activation is the one moment the set changes,
 * and it is also the moment an operator watches the debug page to confirm the switch took effect. Waiting out
 * an expiry there would look exactly like a broken switch. The expiry stays as a safety net for a mutation
 * path nobody wired to the invalidation, not as the mechanism that keeps the value correct.
 *
 * <p><b>Not consulted on the snapshot path.</b> A released application version carries its own frozen set, so
 * it must never be completed from here - that is the very substitution requirement section 4.7 forbids, the
 * one that made a rollback recall nothing.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class ActiveVersionResolver {

    private final DocumentMapper documentMapper;
    private final Cache<String, List<String>> cache;

    public ActiveVersionResolver(DocumentMapper documentMapper, KbProperties properties) {
        this.documentMapper = documentMapper;
        KbProperties.Retrieval retrieval = properties.getRetrieval();
        this.cache = Caffeine.newBuilder()
                .maximumSize(retrieval.getVisibleVersionCacheMaxSize())
                .expireAfterWrite(Duration.ofMinutes(retrieval.getVisibleVersionCacheTtlMinutes()))
                .build();
    }

    /**
     * Active document version ids of a knowledge base.
     *
     * @param kbId knowledge base business id
     * @return active document version ids, empty when the base holds no active version
     */
    public List<String> activeVersionIds(String kbId) {
        return cache.get(kbId, this::load);
    }

    /**
     * Drops the cached set of a knowledge base.
     *
     * @param kbId knowledge base business id, {@code null} ignored
     */
    public void invalidate(String kbId) {
        if (kbId == null) {
            return;
        }
        cache.invalidate(kbId);
        log.info("version visibility set invalidated, kbId={}", kbId);
    }

    private List<String> load(String kbId) {
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .isNotNull(Document::getCurrentVersionId));
        return documents.stream().map(Document::getCurrentVersionId).toList();
    }
}
