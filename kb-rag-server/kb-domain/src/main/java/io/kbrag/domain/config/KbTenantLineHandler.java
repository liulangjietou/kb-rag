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
 * <p>Only the root aggregate tables are fenced; every subordinate table reaches its tenant through
 * its root and is skipped here. Requests without a console principal - the open API, the background
 * task threads, the startup runners - are skipped entirely: they locate rows by exact business id, and
 * the tenant a clause would need is simply not on those threads.
 *
 * <p>One deliberate exception: a caller holding {@code tenant:manage} - the platform operator of the
 * default tenant - sees users and roles across tenants. That is what lets the user management screen
 * create the first account of a fresh tenant and move accounts between tenants; without it the operator
 * could create a tenant nobody can ever log in to. The remaining root tables stay fenced even for the
 * operator: daily work on bases, keys, datasets, apps and memory libraries is pinned to the own tenant.
 *
 * <p><b>The fence covers console threads and nothing else.</b> That is by design, but it means a
 * table whose rows are also read by a background thread is only half isolated by joining the list
 * below - the other half has to be a tenant predicate the code writes itself. {@code
 * t_kb_web_credential} is exactly that case, see the note on the fenced table set.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class KbTenantLineHandler implements TenantLineHandler {

    /**
     * Root aggregate tables carrying a tenant_id column, everything else is reached through them.
     *
     * <p>{@code t_kb_memory_library} joined in V21: it is the root of the memory domain, and the five
     * subordinate memory tables - fragment rules, profile rules, nodes, profiles, keys - reach their
     * tenant through their library, so fencing this one table isolates all six.
     *
     * <p>{@code t_kb_web_credential} joined in V22, and it is the one entry here that does <b>not</b>
     * complete its own isolation. Membership fences the console screen - list, create, edit, delete.
     * The fetch side reads the same table from the scheduled sync thread, which carries no principal,
     * so {@link #ignoreTable} skips it and the clause never appears; that path is isolated only
     * because {@code WebCredentialService#resolveFor} takes the tenant as an argument and puts it in
     * the query by hand. Dropping that argument would silently restore the cross tenant leak this
     * table was added here to fix, and no test of this class would notice.
     *
     * <p>{@code t_kb_source_mapping} joined in V23. It has one background reader, the startup seeder,
     * and that one is handled the way this note prescribes: {@code SourceMappingSeeder} runs with no
     * principal, so the fence skips it entirely and the seeder pins the default tenant by hand. The
     * console side - list, create, copy, edit, delete, and the name lookup the chat import performs -
     * is covered by membership here alone.
     */
    private static final Set<String> FENCED_TABLES = Set.of(
            "t_kb_admin_user", "t_kb_role", "t_kb_knowledge_base",
            "t_kb_api_key", "t_kb_eval_dataset", "t_kb_app", "t_kb_memory_library",
            "t_kb_web_credential", "t_kb_source_mapping");

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
