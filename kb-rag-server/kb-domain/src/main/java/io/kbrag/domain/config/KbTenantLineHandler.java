package io.kbrag.domain.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.model.UserPrincipal;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Fences every root aggregate query to the tenant of the console caller, the M16 contract section 1.3.
 *
 * <p>Only the six root aggregate tables are fenced; every subordinate table reaches its tenant through
 * its root and is skipped here. Requests without a console principal - the open API, the background
 * task threads, the startup runners - are skipped entirely: they locate rows by exact business id, and
 * the tenant a clause would need is simply not on those threads.
 *
 * <p>One deliberate exception: a caller holding {@code tenant:manage} - the platform operator of the
 * default tenant - sees users and roles across tenants. That is what lets the user management screen
 * create the first account of a fresh tenant and move accounts between tenants; without it the operator
 * could create a tenant nobody can ever log in to. The other four root tables stay fenced even for the
 * operator: daily work on bases, keys, datasets and apps is pinned to the own tenant.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class KbTenantLineHandler implements TenantLineHandler {

    /** Root aggregate tables carrying a tenant_id column, everything else is reached through them. */
    private static final Set<String> FENCED_TABLES = Set.of(
            "t_kb_admin_user", "t_kb_role", "t_kb_knowledge_base",
            "t_kb_api_key", "t_kb_eval_dataset", "t_kb_app");

    /** Tables the platform operator may query across tenants, see the class comment. */
    private static final Set<String> OPERATOR_UNFENCED_TABLES = Set.of("t_kb_admin_user", "t_kb_role");

    @Override
    public Expression getTenantId() {
        UserPrincipal principal = UserContextHolder.get();
        // ignoreTable() already refused the statement when there is no principal; the fallback to the
        // default tenant only guards against a principal minted without a tenant by an older cache row.
        String tenantId = principal == null || principal.tenantId() == null
                ? BuiltinTenants.DEFAULT_TENANT_ID : principal.tenantId();
        return new StringValue(tenantId);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (!FENCED_TABLES.contains(tableName)) {
            return true;
        }
        UserPrincipal principal = UserContextHolder.get();
        if (principal == null || principal.tenantId() == null || principal.tenantId().isBlank()) {
            return true;
        }
        return OPERATOR_UNFENCED_TABLES.contains(tableName)
                && principal.hasPermission(PermissionCodes.TENANT_MANAGE);
    }
}
