package io.kbrag.app.appcenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.mapper.AppVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The single point at which a call addressing an application version by its own id resolves the
 * application that owns it.
 *
 * <p><b>This is what makes the tenant fence reach {@code t_kb_app_version}.</b> That table carries no
 * {@code tenant_id} and is not in {@code KbTenantLineHandler.FENCED_TABLES} - correctly so, it is a
 * subordinate of {@code t_kb_app} and reaches its tenant through {@code app_id}. But a subordinate is
 * only isolated while every entry passes through its root first, and the five console entries
 * addressing a version by {@code appVersionId} used to go straight to the subordinate statement: the
 * fence trims nothing there, so any tenant holding {@code app:release} could release or roll back
 * another tenant's application version, and {@code app:read} was enough to read its configuration
 * snapshot - the linked knowledge bases and the model parameters. A release additionally started a
 * dual evaluation run on the gate executor, spending another tenant's retrieval and model calls.
 *
 * <p><b>Why a version costs two statements.</b> {@code app_version_id} exists only in the subordinate
 * table, so the row has to be located there before the application it belongs to is known - that first
 * statement is unavoidable, and it is a bare {@code select} that changes nothing. The decision happens
 * on the second one: the application is read through the fenced root, another tenant's application
 * comes back empty, and the call ends before any statement that writes or any gate that runs.
 *
 * <p><b>One error for both outcomes.</b> A version of another tenant and a version that never existed
 * both answer {@code VERSION_NOT_FOUND} with the same message. Reporting the second hop as
 * "application not found" would be the leak in a different shape: the difference between the two codes
 * would tell a caller that the id they guessed is real and lives elsewhere.
 *
 * <p><b>Tenant only, and that is the whole check here.</b> An application is not owned by a knowledge
 * base - its configuration names the bases it retrieves from - so there is no application level data
 * scope to apply, and the knowledge base half is enforced where the configuration is actually used:
 * {@code KnowledgeApiService#requirePreviewKbAccess} before a preview retrieves, and
 * {@code KbResourceGuard#requireDatasetAccess} before a gate data set is bound. Both of those now run
 * <em>after</em> this resolution, which is the order the M16 contract fixes: tenant answers 404 first,
 * data scope answers 403 second, because the other order leaks "this id exists in another tenant"
 * through the difference between the two status codes.
 *
 * <p><b>The threads with no console principal are deliberately unguarded.</b> On the gate executor and
 * on the preview stream executor there is no principal, so {@code ignoreTable} skips the fence and the
 * root lookup returns the application whatever its tenant. That is the existing M16 semantics for
 * background threads and it opens nothing: those threads only ever see an {@code appVersionId} that
 * one of the guarded entries already resolved on the request thread.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class AppVersionGuard {

    private final AppVersionMapper appVersionMapper;
    private final AppService appService;

    /**
     * Loads a version the current caller may act on, or fails.
     *
     * @param appVersionId version business id
     * @return version row
     * @throws BizException not found when the version does not exist or its application belongs to
     *                      another tenant, the two being indistinguishable from outside
     */
    public AppVersion require(String appVersionId) {
        AppVersion version = appVersionMapper.selectOne(new LambdaQueryWrapper<AppVersion>()
                .eq(AppVersion::getAppVersionId, appVersionId)
                .last("limit 1"));
        if (version == null) {
            throw notFound();
        }
        if (appService.find(version.getAppId()) == null) {
            // The fence read the root as missing, so this version does not exist for this caller
            // either. Reported as the version being absent rather than the application, since the
            // application is not what they asked about and its id is not theirs to learn.
            throw notFound();
        }
        return version;
    }

    private BizException notFound() {
        return new BizException(ErrorCode.VERSION_NOT_FOUND, "application version not found");
    }
}
