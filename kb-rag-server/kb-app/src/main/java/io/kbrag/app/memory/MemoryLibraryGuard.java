package io.kbrag.app.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.MemoryLibrary;
import io.kbrag.domain.mapper.MemoryLibraryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The single point at which a console call resolves the memory library it names.
 *
 * <p><b>This is what makes the tenant fence reach the subordinate tables.</b> Only
 * {@code t_kb_memory_library} carries a {@code tenant_id}, so the MyBatis fence can only trim
 * statements against that one table; rules, nodes, profiles and keys are addressed by
 * {@code library_id} alone and a statement over them would happily cross a tenant boundary. Routing
 * every console entry through this lookup first turns the fence on the root into isolation for all
 * six tables: a library of another tenant is filtered away here, and the call ends before it ever
 * reaches a subordinate statement.
 *
 * <p>A bean rather than a method on {@link MemoryAdminService} because {@link MemoryAppKeyService}
 * needs the same check and is a dependency of the admin service - the reverse edge would be a cycle.
 * Same shape as {@code KbResourceGuard} on the knowledge side, and the same reason: an ownership check
 * has to sit next to the data, since one placed in a controller only guards the paths somebody
 * remembered to guard.
 *
 * <p>A miss answers 404, never 403, which is the M19 isolation stance: outside the caller's
 * intersection the row does not exist as far as the caller can observe.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class MemoryLibraryGuard {

    private final MemoryLibraryMapper memoryLibraryMapper;

    /**
     * Loads a library visible to the current caller, or fails.
     *
     * <p>The tenant clause is added by the MyBatis fence, not written here: on a console thread the
     * statement is trimmed to the caller's tenant and a foreign library simply does not come back.
     * On a thread without a console principal - the open API behind the memory key filter, the
     * background tasks - the fence is skipped and this stays a plain existence check, which is the
     * pre-existing semantics that must not change: a memory key is already bound to exactly one
     * library, so its isolation needs no tenant clause.
     *
     * @param libraryId library business id
     * @return live library row
     * @throws BizException not found when the library does not exist or belongs to another tenant
     */
    public MemoryLibrary requireLibrary(String libraryId) {
        MemoryLibrary library = memoryLibraryMapper.selectOne(new LambdaQueryWrapper<MemoryLibrary>()
                .eq(MemoryLibrary::getLibraryId, libraryId)
                .last("limit 1"));
        if (library == null) {
            throw BizException.notFound("记忆库不存在");
        }
        return library;
    }
}
