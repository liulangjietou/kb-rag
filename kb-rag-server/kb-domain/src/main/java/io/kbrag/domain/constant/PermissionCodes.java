package io.kbrag.domain.constant;

import java.util.Set;

/**
 * Permission codes recognised by the console endpoints.
 *
 * <p>The same codes are seeded as rows by the V16 migration. Both copies are needed and neither is
 * redundant: the table drives the grouped checkbox grid of the role editor, which needs Chinese labels
 * and an ordering that are data, while an endpoint declaring a code needs a compile time symbol so a
 * typo fails the build instead of silently guarding nothing. The migration is the place the two are
 * reconciled.
 *
 * <p>Codes are never removed once shipped. A dropped code would leave rows in
 * {@code t_kb_role_permission} pointing at nothing, and a role editor that quietly forgets a grant on
 * the next save is worse than a stale checkbox.
 *
 * @author owlzhangfq@gmail.com
 */
public final class PermissionCodes {

    /** Read knowledge bases, documents and chunks. */
    public static final String KB_READ = "kb:read";

    /** Create a knowledge base and change its index or retrieval configuration. */
    public static final String KB_WRITE = "kb:write";

    /**
     * Delete a knowledge base.
     *
     * <p>Split off from {@link #KB_WRITE} because it is the only knowledge base operation that destroys
     * an index nobody can rebuild from the console.
     */
    public static final String KB_DELETE = "kb:delete";

    /** Upload, reindex and delete documents, chunks, annotations and data sources. */
    public static final String DOC_WRITE = "doc:write";

    /** Approve or reject a document version, and operate the recycle bin. */
    public static final String DOC_REVIEW = "doc:review";

    /** Run retrieval and chat debugging from the console. */
    public static final String SEARCH_DEBUG = "search:debug";

    /** Triage retrieval feedback and search insights. */
    public static final String FEEDBACK_MANAGE = "feedback:manage";

    /** Read evaluation data sets, cases and run reports. */
    public static final String EVAL_READ = "eval:read";

    /** Maintain evaluation data sets, cases and manual labels. */
    public static final String EVAL_WRITE = "eval:write";

    /**
     * Start an evaluation run.
     *
     * <p>Separate from {@link #EVAL_WRITE} because a run spends model quota, so the account allowed to
     * curate cases is not automatically the account allowed to bill a full sweep.
     */
    public static final String EVAL_RUN = "eval:run";

    /** Read applications and their versions. */
    public static final String APP_READ = "app:read";

    /** Create applications and edit draft version configuration. */
    public static final String APP_WRITE = "app:write";

    /**
     * Promote, publish, roll back or retire an application version.
     *
     * <p>Separate from {@link #APP_WRITE} because these transitions are what outside callers actually
     * see; editing a draft is not.
     */
    public static final String APP_RELEASE = "app:release";

    /** Issue, rotate and revoke outbound API keys. */
    public static final String APIKEY_MANAGE = "apikey:manage";

    /** Read the outbound call audit trail and the retrieval insight reports. */
    public static final String AUDIT_READ = "audit:read";

    /** Change system settings, alert rules and the IK dictionary. */
    public static final String SYSTEM_CONFIG = "system:config";

    /** Create console users, reset passwords, grant roles and disable accounts. */
    public static final String USER_MANAGE = "user:manage";

    /** Create roles, edit their permission set and their knowledge base scope. */
    public static final String ROLE_MANAGE = "role:manage";

    /** Create tenants, rename and suspend them; the only cross tenant vantage point of the console. */
    public static final String TENANT_MANAGE = "tenant:manage";

    /**
     * Change deployment level configuration: the IK dictionary and the alert dispatcher.
     *
     * <p>与 {@link #SYSTEM_CONFIG} 的分界是"这份配置属于谁"：知识库、应用、评测那些设置是租户
     * 自己的，而 IK 词典是 ES 集群级设置（插件按一个 URL 拉一份文档，全部署共用一份分词结果），
     * 告警是运维出口（webhook_url 若可被任意租户改写，就是一条把别家告警内容引出去的信道）。
     * 这两样都不是某个租户的资产，所以不下发到子租户。
     */
    public static final String PLATFORM_CONFIG = "platform:config";

    /** Read memory libraries, rules, memory nodes and run the retrieval debugger. */
    public static final String MEMORY_READ = "memory:read";

    /** Create and delete memory libraries, edit rules and manage memory keys. */
    public static final String MEMORY_WRITE = "memory:write";

    /**
     * Codes only a role of the default tenant may hold.
     *
     * <p>平台级权限码：它们的语义是"站在全平台之上"，一旦落到子租户的角色上，该租户的管理员就拿到了
     * 建租户、停租户以及跨租户查看用户与角色的能力（见 {@code KbTenantLineHandler} 的放行分支），
     * 多租户隔离从根上就没了。{@link #PLATFORM_CONFIG} 是同一类：它改的是全部署共用的一份设施，
     * 一个租户改了，其余租户跟着变。所以这不是一条可以由运营方自行决定的配置，而是一条不变量：
     * 授予入口统一在 {@code RoleService#replacePermissions} 上守。
     */
    public static final Set<String> PLATFORM_ONLY = Set.of(TENANT_MANAGE, PLATFORM_CONFIG);

    private PermissionCodes() {
    }
}
