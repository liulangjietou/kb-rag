package io.kbrag.domain.model;

import io.kbrag.domain.enums.UserSource;

import java.util.Set;

/**
 * The authenticated console caller, resolved once per request from its session token.
 *
 * <p>Flattened on purpose: roles and knowledge base scopes are joined at resolution time and the result
 * is a plain answer to "may this call proceed". An authorisation check that walked the role graph would
 * make every guarded endpoint pay for several queries, and would let a grant edited mid request take
 * effect halfway through one call.
 *
 * <p>The permission set is the <b>union</b> over the roles held, and the knowledge base scope likewise.
 * There are no deny rules: with a union plus denies the outcome depends on evaluation order, which is
 * exactly the sort of rule nobody can predict from a checkbox grid. Narrowing is expressed by granting
 * fewer roles, not by subtracting.
 *
 * @param userId      user business id
 * @param tenantId    owning tenant business id, every root aggregate query is fenced by it
 * @param username    login name, the key session tokens and audit rows are written against
 * @param displayName display label, falls back to the login name
 * @param source      whether the account is local or came from the directory
 * @param roleCodes   codes of the roles held, for display and logging only
 * @param roleIds     business ids of the roles held, what the document ACL rows are matched against
 * @param permissions union of the permission codes granted by those roles
 * @param kbScopeAll  {@code true} when any role held sees every knowledge base
 * @param kbIds       knowledge bases reachable through the scoped roles, ignored when {@code kbScopeAll}
 *
 * @author owlzhangfq@gmail.com
 */
public record UserPrincipal(
        String userId,
        String tenantId,
        String username,
        String displayName,
        UserSource source,
        Set<String> roleCodes,
        Set<String> roleIds,
        Set<String> permissions,
        boolean kbScopeAll,
        Set<String> kbIds) {

    /**
     * Whether the caller carries one permission code.
     *
     * @param code permission code to test
     * @return {@code true} when granted
     */
    public boolean hasPermission(String code) {
        return code != null && permissions != null && permissions.contains(code);
    }

    /**
     * Whether the caller carries at least one of several permission codes.
     *
     * <p>Used by endpoints that serve two audiences, a read screen reachable both by an editor and by a
     * reviewer for instance, where requiring both codes would lock out both.
     *
     * @param codes permission codes to test
     * @return {@code true} when any one is granted
     */
    public boolean hasAnyPermission(String... codes) {
        if (codes == null) {
            return false;
        }
        for (String code : codes) {
            if (hasPermission(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one knowledge base falls inside the data scope of the caller.
     *
     * @param kbId knowledge base business id
     * @return {@code true} when the base is visible
     */
    public boolean canAccessKb(String kbId) {
        if (kbScopeAll) {
            return true;
        }
        return kbId != null && kbIds != null && kbIds.contains(kbId);
    }
}
