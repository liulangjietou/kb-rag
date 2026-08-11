package io.kbrag.app.websource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.WebSource;
import io.kbrag.domain.mapper.WebSourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The single point at which a console call resolves the web registration or the knowledge base it
 * names.
 *
 * <p><b>This is what makes the tenant fence reach {@code t_kb_web_source}.</b> That table carries no
 * {@code tenant_id} and is not in {@code KbTenantLineHandler.FENCED_TABLES} - correctly so, it is a
 * subordinate of {@code t_kb_knowledge_base} and reaches its tenant through {@code kb_id}. But a
 * subordinate is only isolated while every entry passes through its root first, and the entries
 * addressing a registration by {@code source_id} used to go straight to the subordinate statement:
 * the fence trims nothing there, so a caller naming another tenant's source could sync it, flip its
 * switches or hard delete it. Routing those entries through here turns the fence on the root into
 * isolation for the registration table as well.
 *
 * <p><b>Why a registration costs two statements and a base costs one.</b> {@code source_id} exists
 * only in the subordinate table, so it has to be located there before the base it belongs to is
 * known - that first statement is unavoidable, and it is a bare {@code select} that changes nothing.
 * The decision happens on the second one: the base is read through the fenced root, another tenant's
 * base comes back empty, and the call ends before any statement that writes or fetches. An entry
 * that already names a {@code kb_id} skips the first hop entirely and touches the subordinate table
 * zero times.
 *
 * <p>A bean rather than a method on {@link WebSourceService} for the same reason
 * {@code MemoryLibraryGuard} is one on the memory side: an ownership check has to sit next to the
 * data, and one placed in a controller only guards the paths somebody remembered to guard. Keeping
 * it here also makes the obligation greppable - every console entry of this domain resolves through
 * this class, and a new one that does not is visible in review.
 *
 * <p><b>Tenant first, data scope second.</b> A base outside the caller's tenant answers 404 - the
 * M16 isolation stance, outside the caller's tenant the row does not exist as far as they can
 * observe. Only inside the own tenant does the data scope of the caller's roles apply, and that one
 * answers 403: a base of the own tenant that a role does not name is a permission problem the
 * operator can act on. Running the two in the other order would leak "this id exists elsewhere"
 * through the difference between the two codes.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class WebSourceGuard {

    private final WebSourceMapper webSourceMapper;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * A registration together with the base that owns it, as resolved for one console call.
     *
     * <p>The two travel together because the caller needs both and they must describe the same
     * resolution: the tenant a sync spends credentials of is the tenant of <em>this</em> base, not
     * whatever a second lookup would return a moment later.
     *
     * @param source registration row
     * @param base   knowledge base the registration feeds
     */
    public record ScopedWebSource(WebSource source, KnowledgeBase base) {
    }

    /**
     * Loads a knowledge base the current caller may act on, or fails.
     *
     * <p>The tenant clause is added by the MyBatis fence, not written here: on a console thread the
     * statement is trimmed to the caller's tenant and a foreign base simply does not come back.
     *
     * @param kbId knowledge base business id
     * @return live base row
     * @throws BizException not found when the base does not exist or belongs to another tenant,
     *                      forbidden when it sits outside the caller's data scope
     */
    public KnowledgeBase requireBase(String kbId) {
        KnowledgeBase base = knowledgeBaseService.find(kbId);
        if (base == null) {
            throw BizException.notFound("知识库不存在：" + kbId);
        }
        requireScope(base.getKbId());
        return base;
    }

    /**
     * Loads a registration the current caller may act on, together with the base that owns it.
     *
     * <p>A registration of another tenant is reported exactly as a registration that never existed,
     * so the two are indistinguishable from outside - which is the point.
     *
     * @param sourceId registration business id
     * @return registration and its base
     * @throws BizException not found when the registration does not exist or its base belongs to
     *                      another tenant, forbidden when that base sits outside the caller's data
     *                      scope
     */
    public ScopedWebSource requireSource(String sourceId) {
        WebSource source = webSourceMapper.selectOne(new LambdaQueryWrapper<WebSource>()
                .eq(WebSource::getSourceId, sourceId)
                .last("limit 1"));
        if (source == null) {
            throw BizException.notFound("URL 登记不存在：" + sourceId);
        }
        KnowledgeBase base = knowledgeBaseService.find(source.getKbId());
        if (base == null) {
            // The fence read the root as missing, so this registration does not exist for this
            // caller either. Reported as the registration being absent rather than the base, since
            // the base is not what they asked about and its id is not theirs to learn.
            throw BizException.notFound("URL 登记不存在：" + sourceId);
        }
        requireScope(base.getKbId());
        return new ScopedWebSource(source, base);
    }

    /**
     * Applies the data scope of the caller to an already tenant-resolved base.
     *
     * <p>Skipped without a console principal, which is the shape every scope check in this codebase
     * has: a thread with no principal - the open API, the scheduled pass - carries no data scope to
     * trim against, and an empty one there would mean "sees nothing" rather than "sees everything".
     *
     * @param kbId knowledge base business id
     */
    private void requireScope(String kbId) {
        if (!AccessGuard.unrestrictedKbScope()) {
            AccessGuard.requireKbAccess(kbId);
        }
    }
}
