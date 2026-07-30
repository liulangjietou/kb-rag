package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.enums.RoleGrantSource;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives console roles from directory group membership on every single sign on login.
 *
 * <p>The mapping from group to role lives in this system's configuration, never in the directory:
 * an unmapped group grants nothing, so whoever reorganises an organisational unit cannot hand out
 * authorisation here by accident. What the directory decides is membership; what a membership means
 * stays a decision of the deployment.
 *
 * <p>Synchronised grants are replaced in full on every login and are marked {@code LDAP_SYNC}, while
 * {@code MANUAL} grants are never touched. Both halves of that rule guard against the same class of
 * incident: a role that appears or disappears without anyone being able to say why. An operator's
 * grant revoked by a night time login is untraceable; a directory grant surviving group removal makes
 * the synchronisation meaningless.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectoryGroupSyncService implements ApplicationRunner {

    private static final String ENTRY_SEPARATOR = ";";
    private static final char CODE_SEPARATOR = '=';

    private final KbProperties properties;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PrincipalResolver principalResolver;

    /**
     * Warns at startup when synchronisation is switched on without a single usable mapping.
     *
     * <p>That combination is almost always a configuration slip - the sync would then revoke every
     * synchronised role on the next login and grant nothing back, which looks like a permission bug.
     */
    @Override
    public void run(ApplicationArguments args) {
        KbProperties.Auth.Ldap.GroupSync groupSync = properties.getAuth().getLdap().getGroupSync();
        if (groupSync.isEnabled() && parseMappings(groupSync.getRoleMappings()).isEmpty()) {
            log.error("directory group sync is enabled but no valid role mapping is configured, "
                            + "errorCode={} - every login will strip synchronised roles and grant "
                            + "none back",
                    ErrorCode.INVALID_PARAM);
        }
    }

    /**
     * Whether roles are derived from directory groups at all.
     *
     * @return {@code true} when the deployment enabled group synchronisation
     */
    public boolean enabled() {
        return properties.getAuth().getLdap().getGroupSync().isEnabled();
    }

    /**
     * Replaces the synchronised role set of one account with what its current groups map to.
     *
     * <p>Delete first, then insert: the synchronised set has no identity worth diffing, and a full
     * replacement is idempotent no matter what a previous crash left behind. The principal cache is
     * evicted afterwards so the new roles apply to the session being opened right now.
     *
     * @param user     account that just authenticated against the directory
     * @param groupDns distinguished names of the groups the directory reported, may be empty
     */
    @Transactional(rollbackFor = Exception.class)
    public void sync(AdminUser user, List<String> groupDns) {
        Map<String, String> mappings = parseMappings(
                properties.getAuth().getLdap().getGroupSync().getRoleMappings());
        Set<String> roleCodes = matchedRoleCodes(groupDns, mappings);
        Set<String> roleIds = resolveRoleIds(roleCodes, user.getTenantId());

        userRoleMapper.deleteByUserIdAndGrantedBy(user.getUserId(), RoleGrantSource.LDAP_SYNC.name());
        for (String roleId : roleIds) {
            UserRole binding = new UserRole();
            binding.setUserId(user.getUserId());
            binding.setRoleId(roleId);
            binding.setGrantedBy(RoleGrantSource.LDAP_SYNC);
            userRoleMapper.insert(binding);
        }
        principalResolver.evict(user.getUsername());
        log.info("directory group sync applied, userId={}, groups={}, roles={}",
                user.getUserId(), groupDns == null ? 0 : groupDns.size(), roleIds.size());
    }

    /**
     * Parses {@code groupDn=ROLE_CODE} entries into a lookup of normalised DN to role code.
     *
     * <p>Malformed entries are skipped with a warning instead of failing the login: the mapping is a
     * deployment knob, and one typo must not take single sign on down for everyone.
     */
    Map<String, String> parseMappings(String raw) {
        Map<String, String> mappings = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return mappings;
        }
        for (String entry : raw.split(ENTRY_SEPARATOR)) {
            if (entry.isBlank()) {
                continue;
            }
            // The DN itself is full of '=' characters, so only the last one can separate the role
            // code - "CN=kb-admins,DC=corp=KB_ADMIN" maps "CN=kb-admins,DC=corp" to "KB_ADMIN".
            int separator = entry.lastIndexOf(CODE_SEPARATOR);
            String dn = separator > 0 ? normalizeDn(entry.substring(0, separator)) : "";
            String code = separator > 0 ? entry.substring(separator + 1).trim() : "";
            if (dn.isEmpty() || code.isEmpty()) {
                log.error("directory group mapping entry is malformed and skipped, errorCode={}, "
                        + "entry={}", ErrorCode.INVALID_PARAM, entry);
                continue;
            }
            mappings.put(dn, code);
        }
        return mappings;
    }

    private Set<String> matchedRoleCodes(List<String> groupDns, Map<String, String> mappings) {
        Set<String> codes = new LinkedHashSet<>();
        if (CollectionUtils.isEmpty(groupDns) || mappings.isEmpty()) {
            return codes;
        }
        for (String groupDn : groupDns) {
            String code = mappings.get(normalizeDn(groupDn));
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * Resolves mapped role codes to role ids inside the tenant of the account.
     *
     * <p>A code that resolves nowhere is skipped with a warning rather than failing the login: the
     * mapping may name a role an operator has since deleted, and the person still authenticated.
     */
    private Set<String> resolveRoleIds(Set<String> roleCodes, String tenantId) {
        Set<String> roleIds = new LinkedHashSet<>();
        if (CollectionUtils.isEmpty(roleCodes)) {
            return roleIds;
        }
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .in(Role::getCode, roleCodes));
        for (String code : roleCodes) {
            Role match = roles.stream()
                    .filter(role -> code.equals(role.getCode()))
                    // A role of another tenant must never be granted through the mapping, or the
                    // sync becomes the cross tenant leak the row fence exists to prevent.
                    .filter(role -> sameTenant(role.getTenantId(), tenantId))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                log.error("directory group mapping names an unknown role and is skipped, "
                                + "errorCode={}, roleCode={}, tenantId={}",
                        ErrorCode.INVALID_PARAM, code, tenantId);
                continue;
            }
            roleIds.add(match.getRoleId());
        }
        return roleIds;
    }

    private boolean sameTenant(String left, String right) {
        if (BuiltinTenants.isDefault(left) && BuiltinTenants.isDefault(right)) {
            return true;
        }
        return left != null && left.equals(right);
    }

    /**
     * Folds a distinguished name to its comparison form: lower case with all whitespace removed.
     *
     * <p>Directories return DNs with inconsistent casing and spacing between deployments, and a
     * mapping that silently misses because of {@code "CN=x, DC=y"} versus {@code "cn=x,dc=y"} is the
     * kind of misconfiguration nobody spots in a review.
     */
    private String normalizeDn(String dn) {
        return dn == null ? "" : dn.replaceAll("\\s+", "").toLowerCase();
    }
}
