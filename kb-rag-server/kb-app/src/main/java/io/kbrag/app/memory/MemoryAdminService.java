package io.kbrag.app.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.MemoryFragmentRule;
import io.kbrag.domain.entity.MemoryLibrary;
import io.kbrag.domain.entity.MemoryNode;
import io.kbrag.domain.entity.MemoryProfileRule;
import io.kbrag.domain.enums.MemoryExtractVersion;
import io.kbrag.domain.enums.MemoryInstructionType;
import io.kbrag.domain.mapper.MemoryFragmentRuleMapper;
import io.kbrag.domain.mapper.MemoryLibraryMapper;
import io.kbrag.domain.mapper.MemoryNodeMapper;
import io.kbrag.domain.mapper.MemoryProfileMapper;
import io.kbrag.domain.mapper.MemoryProfileRuleMapper;
import io.kbrag.domain.model.MemoryProfileField;
import io.kbrag.domain.port.MemoryStore;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Console side administration of memory libraries, the M19 contract.
 *
 * <p>Everything the open API in {@link MemoryApiService} treats as given - the library, its rules,
 * its keys - is created and maintained here. The two services share the same isolation stance:
 * every statement filters on the library id taken from the URL path, and a row that does not
 * belong to that library answers 404, never 403.
 *
 * <p><b>Every entry taking a library id resolves it through {@link MemoryLibraryGuard} first</b>,
 * including the ones that address a rule or a node directly. Those could locate their row from the
 * library id in the path alone, but only the library table carries a {@code tenant_id}: skipping the
 * lookup would leave the subordinate statement outside the tenant fence, which is exactly how a
 * caller of another tenant used to edit rules and keys it named by id.
 *
 * <p>The two entries without a library id - {@link #pageLibraries} and {@link #createLibrary} - are
 * covered by the fence directly rather than by the guard: it trims the listing to the caller's tenant
 * and stamps the tenant onto the insert. They are the only memory statements with no second line of
 * defence, so anything that would take them off the fence (a hand written mapper query, an
 * {@code @InterceptorIgnore}) removes their isolation outright.
 *
 * <p>Cascading deletions are deliberately not one transaction. They fan out over MySQL and
 * Elasticsearch, and holding a connection across the ES round trip is the same pool hazard the add
 * path avoids around LLM calls. Instead the parent row is removed <em>last</em>, so a cascade that
 * fails halfway leaves the parent visible and the operator can simply retry the deletion.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryAdminService {

    /** Rule count ceiling per library, both fragment and profile rules, the Bailian limit. */
    public static final int MAX_RULES_PER_LIBRARY = 50;

    /** Field count ceiling of one profile rule. */
    public static final int MAX_PROFILE_FIELDS = 50;

    /** Lifetimes a fragment rule may choose from; {@code null} (never expiring) is also allowed. */
    private static final Set<Integer> ALLOWED_EXPIRE_DAYS = Set.of(7, 30, 180);

    /** Name of the fragment rule seeded into every new library, aligned with Bailian. */
    private static final String BUILTIN_RULE_NAME = "默认项目";

    /** Lifetime of the seeded default rule. */
    private static final int BUILTIN_EXPIRE_DAYS = 180;

    private final MemoryLibraryGuard memoryLibraryGuard;
    private final MemoryLibraryMapper memoryLibraryMapper;
    private final MemoryFragmentRuleMapper memoryFragmentRuleMapper;
    private final MemoryProfileRuleMapper memoryProfileRuleMapper;
    private final MemoryNodeMapper memoryNodeMapper;
    private final MemoryProfileMapper memoryProfileMapper;
    private final MemoryAppKeyService memoryAppKeyService;
    private final MemoryProfileService memoryProfileService;
    private final MemoryApiService memoryApiService;
    private final MemoryStore memoryStore;
    private final BizIdGenerator bizIdGenerator;

    // ------------------------------------------------------------------ libraries

    /**
     * Pages libraries with their rule and memory statistics, newest first.
     *
     * @param keyword optional name filter, contains match
     * @param pageNum 1-based page number
     * @param size    page size
     * @return page of library views
     */
    public LibraryPage pageLibraries(String keyword, int pageNum, int size) {
        Page<MemoryLibrary> page = memoryLibraryMapper.selectPage(new Page<>(pageNum, size),
                new LambdaQueryWrapper<MemoryLibrary>()
                        .like(keyword != null && !keyword.isBlank(), MemoryLibrary::getName, keyword)
                        .orderByDesc(MemoryLibrary::getId));
        List<LibraryView> items = page.getRecords().stream().map(this::viewOf).toList();
        return new LibraryPage(items, (int) page.getCurrent(), (int) page.getSize(), page.getTotal());
    }

    /**
     * Creates a library and seeds its built in default fragment rule.
     *
     * <p>The seed is what lets an agent call AddMemory right after receiving its key, without a
     * console round trip to define a rule first - the same out of the box behaviour Bailian ships.
     *
     * @param name        display name, unique among live libraries
     * @param description optional description
     * @return created library with zeroed statistics
     */
    @Transactional
    public LibraryView createLibrary(String name, String description) {
        requireUniqueLibraryName(name, null);
        MemoryLibrary library = new MemoryLibrary();
        library.setLibraryId(bizIdGenerator.memoryLibraryId());
        library.setName(name.trim());
        library.setDescription(blankToNull(description));
        memoryLibraryMapper.insert(library);

        MemoryFragmentRule rule = new MemoryFragmentRule();
        rule.setRuleId(bizIdGenerator.memoryFragmentRuleId());
        rule.setLibraryId(library.getLibraryId());
        rule.setName(BUILTIN_RULE_NAME);
        rule.setInstructionType(MemoryInstructionType.DEFAULT);
        rule.setAutoUpdate(1);
        rule.setExpireDays(BUILTIN_EXPIRE_DAYS);
        rule.setExtractVersion(MemoryExtractVersion.PRO);
        rule.setBuiltin(1);
        memoryFragmentRuleMapper.insert(rule);
        log.info("memory library created, libraryId={}, builtinRuleId={}",
                library.getLibraryId(), rule.getRuleId());
        return viewOf(library);
    }

    /**
     * Loads one library with statistics and both rule lists.
     *
     * @param libraryId library business id
     * @return detail view
     */
    public LibraryDetail libraryDetail(String libraryId) {
        MemoryLibrary library = requireLibrary(libraryId);
        return new LibraryDetail(viewOf(library), listFragmentRules(libraryId),
                listProfileRules(libraryId));
    }

    /**
     * Renames a library or replaces its description.
     *
     * @param libraryId   library business id
     * @param name        new display name, unique among live libraries
     * @param description new description
     * @return updated library view
     */
    public LibraryView updateLibrary(String libraryId, String name, String description) {
        MemoryLibrary library = requireLibrary(libraryId);
        requireUniqueLibraryName(name, libraryId);
        library.setName(name.trim());
        library.setDescription(blankToNull(description));
        memoryLibraryMapper.updateById(library);
        return viewOf(library);
    }

    /**
     * Deletes a library and everything inside it: rules, nodes, profiles, keys, search copies.
     *
     * <p>Leaf data first, the library row last - see the class comment for why this is a retryable
     * sequence instead of a transaction.
     *
     * @param libraryId library business id
     */
    public void deleteLibrary(String libraryId) {
        MemoryLibrary library = requireLibrary(libraryId);
        memoryAppKeyService.deleteByLibrary(libraryId);
        memoryFragmentRuleMapper.delete(new LambdaQueryWrapper<MemoryFragmentRule>()
                .eq(MemoryFragmentRule::getLibraryId, libraryId));
        memoryProfileRuleMapper.delete(new LambdaQueryWrapper<MemoryProfileRule>()
                .eq(MemoryProfileRule::getLibraryId, libraryId));
        memoryNodeMapper.delete(new LambdaQueryWrapper<MemoryNode>()
                .eq(MemoryNode::getLibraryId, libraryId));
        memoryProfileMapper.hardDeleteByLibraryId(libraryId);
        memoryStore.deleteByLibrary(libraryId);
        memoryLibraryMapper.deleteById(library.getId());
        log.info("memory library deleted, libraryId={}", libraryId);
    }

    /**
     * Loads a library visible to the caller, or answers 404. Every entry taking a library id starts
     * here, see the class comment.
     *
     * @param libraryId library business id
     * @return live library row
     */
    private MemoryLibrary requireLibrary(String libraryId) {
        return memoryLibraryGuard.requireLibrary(libraryId);
    }

    // ------------------------------------------------------------------ fragment rules

    /**
     * Lists the fragment rules of one library with their node counts, oldest first so the seeded
     * default rule stays on top.
     *
     * @param libraryId library business id
     * @return rule views
     */
    public List<FragmentRuleView> listFragmentRules(String libraryId) {
        requireLibrary(libraryId);
        Map<String, Long> counts = nodeCountsByRule(libraryId);
        return memoryFragmentRuleMapper.selectList(new LambdaQueryWrapper<MemoryFragmentRule>()
                        .eq(MemoryFragmentRule::getLibraryId, libraryId)
                        .orderByAsc(MemoryFragmentRule::getId))
                .stream()
                .map(rule -> new FragmentRuleView(rule, counts.getOrDefault(rule.getRuleId(), 0L)))
                .toList();
    }

    /**
     * Adds a fragment rule to a library.
     *
     * @param libraryId library business id
     * @param command   validated rule payload
     * @return created rule view
     */
    public FragmentRuleView createFragmentRule(String libraryId, FragmentRuleCommand command) {
        requireLibrary(libraryId);
        validateFragmentRule(command);
        requireUniqueFragmentRuleName(libraryId, command.name(), null);
        long count = memoryFragmentRuleMapper.selectCount(new LambdaQueryWrapper<MemoryFragmentRule>()
                .eq(MemoryFragmentRule::getLibraryId, libraryId));
        if (count >= MAX_RULES_PER_LIBRARY) {
            throw BizException.invalidParam("记忆片段规则已达上限 " + MAX_RULES_PER_LIBRARY + " 条");
        }
        MemoryFragmentRule rule = new MemoryFragmentRule();
        rule.setRuleId(bizIdGenerator.memoryFragmentRuleId());
        rule.setLibraryId(libraryId);
        rule.setBuiltin(0);
        applyFragmentRule(rule, command);
        memoryFragmentRuleMapper.insert(rule);
        return new FragmentRuleView(rule, 0L);
    }

    /**
     * Edits a fragment rule; the built in rule is editable like any other.
     *
     * @param libraryId library business id
     * @param ruleId    rule business id
     * @param command   validated rule payload
     * @return updated rule view
     */
    public FragmentRuleView updateFragmentRule(String libraryId, String ruleId,
                                               FragmentRuleCommand command) {
        MemoryFragmentRule rule = requireFragmentRule(libraryId, ruleId);
        validateFragmentRule(command);
        requireUniqueFragmentRuleName(libraryId, command.name(), ruleId);
        applyFragmentRule(rule, command);
        memoryFragmentRuleMapper.updateById(rule);
        return new FragmentRuleView(rule, nodeCountsByRule(libraryId).getOrDefault(ruleId, 0L));
    }

    /**
     * Deletes a fragment rule together with every memory it produced.
     *
     * @param libraryId library business id
     * @param ruleId    rule business id
     */
    public void deleteFragmentRule(String libraryId, String ruleId) {
        MemoryFragmentRule rule = requireFragmentRule(libraryId, ruleId);
        if (rule.getBuiltin() != null && rule.getBuiltin() == 1) {
            throw BizException.invalidParam("预置默认规则不可删除");
        }
        memoryNodeMapper.delete(new LambdaQueryWrapper<MemoryNode>()
                .eq(MemoryNode::getLibraryId, libraryId)
                .eq(MemoryNode::getRuleId, ruleId));
        memoryStore.deleteByRule(libraryId, ruleId);
        memoryFragmentRuleMapper.deleteById(rule.getId());
        log.info("memory fragment rule deleted, libraryId={}, ruleId={}", libraryId, ruleId);
    }

    // ------------------------------------------------------------------ profile rules

    /**
     * Lists the profile rules of one library with their parsed field definitions.
     *
     * @param libraryId library business id
     * @return rule views
     */
    public List<ProfileRuleView> listProfileRules(String libraryId) {
        requireLibrary(libraryId);
        return memoryProfileRuleMapper.selectList(new LambdaQueryWrapper<MemoryProfileRule>()
                        .eq(MemoryProfileRule::getLibraryId, libraryId)
                        .orderByAsc(MemoryProfileRule::getId))
                .stream()
                .map(rule -> new ProfileRuleView(rule, memoryProfileService.fieldsOf(rule)))
                .toList();
    }

    /**
     * Adds a profile rule to a library.
     *
     * @param libraryId library business id
     * @param command   validated rule payload
     * @return created rule view
     */
    public ProfileRuleView createProfileRule(String libraryId, ProfileRuleCommand command) {
        requireLibrary(libraryId);
        validateProfileFields(command.fields());
        requireUniqueProfileRuleName(libraryId, command.name(), null);
        long count = memoryProfileRuleMapper.selectCount(new LambdaQueryWrapper<MemoryProfileRule>()
                .eq(MemoryProfileRule::getLibraryId, libraryId));
        if (count >= MAX_RULES_PER_LIBRARY) {
            throw BizException.invalidParam("用户画像规则已达上限 " + MAX_RULES_PER_LIBRARY + " 条");
        }
        MemoryProfileRule rule = new MemoryProfileRule();
        rule.setRuleId(bizIdGenerator.memoryProfileRuleId());
        rule.setLibraryId(libraryId);
        applyProfileRule(rule, command);
        memoryProfileRuleMapper.insert(rule);
        return new ProfileRuleView(rule, command.fields());
    }

    /**
     * Edits a profile rule; already extracted profiles keep their values, only fields that stay
     * defined remain visible through the read side.
     *
     * @param libraryId library business id
     * @param ruleId    rule business id
     * @param command   validated rule payload
     * @return updated rule view
     */
    public ProfileRuleView updateProfileRule(String libraryId, String ruleId,
                                             ProfileRuleCommand command) {
        MemoryProfileRule rule = requireProfileRule(libraryId, ruleId);
        validateProfileFields(command.fields());
        requireUniqueProfileRuleName(libraryId, command.name(), ruleId);
        applyProfileRule(rule, command);
        memoryProfileRuleMapper.updateById(rule);
        return new ProfileRuleView(rule, command.fields());
    }

    /**
     * Deletes a profile rule together with every profile extracted under it.
     *
     * <p>Profile rows go physically - a soft deleted row would hold the rule/user unique key
     * hostage, see {@link MemoryProfileMapper}.
     *
     * @param libraryId library business id
     * @param ruleId    rule business id
     */
    public void deleteProfileRule(String libraryId, String ruleId) {
        MemoryProfileRule rule = requireProfileRule(libraryId, ruleId);
        memoryProfileMapper.hardDeleteByRuleId(ruleId);
        memoryProfileRuleMapper.deleteById(rule.getId());
        log.info("memory profile rule deleted, libraryId={}, ruleId={}", libraryId, ruleId);
    }

    // ------------------------------------------------------------------ entities, nodes, profiles

    /**
     * Pages the memory entities of one library: user ids aggregated over live nodes with a node
     * count and the latest write time, most recently written first.
     *
     * @param libraryId library business id
     * @param userId    optional exact entity filter
     * @param pageNum   1-based page number
     * @param size      page size
     * @return page of entity views
     */
    public EntityPage pageEntities(String libraryId, String userId, int pageNum, int size) {
        requireLibrary(libraryId);
        QueryWrapper<MemoryNode> wrapper = new QueryWrapper<MemoryNode>()
                .select("user_id AS userId", "count(*) AS nodeCount", "max(updated_at) AS lastUpdated")
                .eq("library_id", libraryId)
                .eq(userId != null && !userId.isBlank(), "user_id", userId)
                .groupBy("user_id")
                .orderByDesc("max(updated_at)");
        Page<Map<String, Object>> page = memoryNodeMapper.selectMapsPage(new Page<>(pageNum, size), wrapper);
        List<EntityView> items = page.getRecords().stream()
                .map(row -> new EntityView(String.valueOf(row.get("userId")),
                        ((Number) row.get("nodeCount")).longValue(),
                        toLocalDateTime(row.get("lastUpdated"))))
                .toList();
        return new EntityPage(items, (int) page.getCurrent(), (int) page.getSize(), page.getTotal());
    }

    /**
     * Pages the nodes of one entity for the console detail view, newest first, expired included -
     * the operator is entitled to see what the open API no longer recalls.
     *
     * @param libraryId library business id
     * @param userId    memory entity id
     * @param ruleId    optional fragment rule filter
     * @param pageNum   1-based page number
     * @param size      page size
     * @return page of nodes
     */
    public Page<MemoryNode> pageNodes(String libraryId, String userId, String ruleId,
                                      int pageNum, int size) {
        requireLibrary(libraryId);
        return memoryNodeMapper.selectPage(new Page<>(pageNum, size),
                new LambdaQueryWrapper<MemoryNode>()
                        .eq(MemoryNode::getLibraryId, libraryId)
                        .eq(MemoryNode::getUserId, userId)
                        .eq(ruleId != null && !ruleId.isBlank(), MemoryNode::getRuleId, ruleId)
                        .orderByDesc(MemoryNode::getId));
    }

    /**
     * Deletes one node from the console, no entity id required - the operator sees the whole
     * library.
     *
     * @param libraryId library business id
     * @param nodeId    node business id
     */
    public void deleteNode(String libraryId, String nodeId) {
        requireLibrary(libraryId);
        MemoryNode node = memoryNodeMapper.selectOne(new LambdaQueryWrapper<MemoryNode>()
                .eq(MemoryNode::getLibraryId, libraryId)
                .eq(MemoryNode::getNodeId, nodeId));
        if (node == null) {
            throw BizException.notFound("记忆节点不存在");
        }
        memoryNodeMapper.deleteById(node.getId());
        memoryStore.delete(nodeId);
    }

    /**
     * The console view of an entity's profiles, same shape the open API serves.
     *
     * @param libraryId library business id
     * @param userId    memory entity id
     * @param ruleId    optional profile rule filter
     * @return profile per rule
     */
    public List<MemoryProfileService.ProfileView> profiles(String libraryId, String userId,
                                                           String ruleId) {
        requireLibrary(libraryId);
        return memoryProfileService.viewsOf(libraryId, userId, ruleId);
    }

    /**
     * Runs the open API search pipeline on behalf of the console, for tuning recall parameters
     * before an agent goes live.
     *
     * @param libraryId library business id
     * @param command   search parameters, same semantics as the open API
     * @return search outcome
     */
    public MemorySearchOutcome searchDebug(String libraryId, MemorySearchCommand command) {
        MemoryLibrary library = requireLibrary(libraryId);
        return memoryApiService.search(
                new MemoryKeyPrincipal("console", library.getTenantId(), libraryId, "search-debug", 0), command);
    }

    // ------------------------------------------------------------------ internals

    private LibraryView viewOf(MemoryLibrary library) {
        String libraryId = library.getLibraryId();
        long fragmentRules = memoryFragmentRuleMapper.selectCount(
                new LambdaQueryWrapper<MemoryFragmentRule>()
                        .eq(MemoryFragmentRule::getLibraryId, libraryId));
        long profileRules = memoryProfileRuleMapper.selectCount(
                new LambdaQueryWrapper<MemoryProfileRule>()
                        .eq(MemoryProfileRule::getLibraryId, libraryId));
        long nodes = memoryNodeMapper.selectCount(new LambdaQueryWrapper<MemoryNode>()
                .eq(MemoryNode::getLibraryId, libraryId));
        Object entities = memoryNodeMapper.selectObjs(new QueryWrapper<MemoryNode>()
                        .select("count(DISTINCT user_id)")
                        .eq("library_id", libraryId))
                .stream().findFirst().orElse(0L);
        return new LibraryView(library, fragmentRules, profileRules, nodes,
                ((Number) entities).longValue());
    }

    private Map<String, Long> nodeCountsByRule(String libraryId) {
        Map<String, Long> counts = new HashMap<>();
        memoryNodeMapper.selectMaps(new QueryWrapper<MemoryNode>()
                        .select("rule_id AS ruleId", "count(*) AS nodeCount")
                        .eq("library_id", libraryId)
                        .groupBy("rule_id"))
                .forEach(row -> counts.put(String.valueOf(row.get("ruleId")),
                        ((Number) row.get("nodeCount")).longValue()));
        return counts;
    }

    private MemoryFragmentRule requireFragmentRule(String libraryId, String ruleId) {
        // Resolving the library first is the tenant check, see the class comment; the rule table
        // itself carries no tenant_id and would answer a caller of any tenant.
        requireLibrary(libraryId);
        MemoryFragmentRule rule = memoryFragmentRuleMapper.selectOne(
                new LambdaQueryWrapper<MemoryFragmentRule>()
                        .eq(MemoryFragmentRule::getLibraryId, libraryId)
                        .eq(MemoryFragmentRule::getRuleId, ruleId));
        if (rule == null) {
            throw BizException.notFound("记忆片段规则不存在");
        }
        return rule;
    }

    private MemoryProfileRule requireProfileRule(String libraryId, String ruleId) {
        requireLibrary(libraryId);
        MemoryProfileRule rule = memoryProfileRuleMapper.selectOne(
                new LambdaQueryWrapper<MemoryProfileRule>()
                        .eq(MemoryProfileRule::getLibraryId, libraryId)
                        .eq(MemoryProfileRule::getRuleId, ruleId));
        if (rule == null) {
            throw BizException.notFound("用户画像规则不存在");
        }
        return rule;
    }

    private void validateFragmentRule(FragmentRuleCommand command) {
        if (command.instructionType() == MemoryInstructionType.CUSTOM
                && (command.instruction() == null || command.instruction().isBlank())) {
            throw BizException.invalidParam("自定义指令类型必须填写 instruction");
        }
        if (command.expireDays() != null && !ALLOWED_EXPIRE_DAYS.contains(command.expireDays())) {
            throw BizException.invalidParam("expire_days 仅支持 7、30、180 或不填（永不过期）");
        }
    }

    private void applyFragmentRule(MemoryFragmentRule rule, FragmentRuleCommand command) {
        rule.setName(command.name().trim());
        rule.setInstructionType(command.instructionType());
        rule.setInstruction(command.instructionType() == MemoryInstructionType.CUSTOM
                ? command.instruction().trim() : null);
        rule.setAutoUpdate(command.autoUpdate() ? 1 : 0);
        rule.setExpireDays(command.expireDays());
        rule.setExtractVersion(command.extractVersion() == null
                ? MemoryExtractVersion.PRO : command.extractVersion());
    }

    private void applyProfileRule(MemoryProfileRule rule, ProfileRuleCommand command) {
        rule.setName(command.name().trim());
        rule.setExtractVersion(command.extractVersion() == null
                ? MemoryExtractVersion.PRO : command.extractVersion());
        rule.setFields(JsonUtil.toJson(command.fields()));
    }

    private void validateProfileFields(List<MemoryProfileField> fields) {
        if (fields == null || fields.isEmpty()) {
            throw BizException.invalidParam("画像字段至少定义 1 个");
        }
        if (fields.size() > MAX_PROFILE_FIELDS) {
            throw BizException.invalidParam("画像字段最多定义 " + MAX_PROFILE_FIELDS + " 个");
        }
        Set<String> names = new HashSet<>();
        for (MemoryProfileField field : fields) {
            if (field.name() == null || field.name().isBlank()) {
                throw BizException.invalidParam("画像字段名称不能为空");
            }
            if (!names.add(field.name().trim())) {
                throw BizException.invalidParam("画像字段名称重复：" + field.name().trim());
            }
        }
    }

    private void requireUniqueLibraryName(String name, String selfLibraryId) {
        Long clashes = memoryLibraryMapper.selectCount(new LambdaQueryWrapper<MemoryLibrary>()
                .eq(MemoryLibrary::getName, name.trim())
                .ne(selfLibraryId != null, MemoryLibrary::getLibraryId, selfLibraryId));
        if (clashes > 0) {
            throw BizException.invalidParam("记忆库名称已存在");
        }
    }

    private void requireUniqueFragmentRuleName(String libraryId, String name, String selfRuleId) {
        Long clashes = memoryFragmentRuleMapper.selectCount(new LambdaQueryWrapper<MemoryFragmentRule>()
                .eq(MemoryFragmentRule::getLibraryId, libraryId)
                .eq(MemoryFragmentRule::getName, name.trim())
                .ne(selfRuleId != null, MemoryFragmentRule::getRuleId, selfRuleId));
        if (clashes > 0) {
            throw BizException.invalidParam("规则名称在库内已存在");
        }
    }

    private void requireUniqueProfileRuleName(String libraryId, String name, String selfRuleId) {
        Long clashes = memoryProfileRuleMapper.selectCount(new LambdaQueryWrapper<MemoryProfileRule>()
                .eq(MemoryProfileRule::getLibraryId, libraryId)
                .eq(MemoryProfileRule::getName, name.trim())
                .ne(selfRuleId != null, MemoryProfileRule::getRuleId, selfRuleId));
        if (clashes > 0) {
            throw BizException.invalidParam("规则名称在库内已存在");
        }
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** One library plus the statistics its list and detail cards show. */
    public record LibraryView(MemoryLibrary library, long fragmentRuleCount, long profileRuleCount,
                              long nodeCount, long entityCount) {
    }

    /** One console page of libraries. */
    public record LibraryPage(List<LibraryView> items, int page, int size, long total) {
    }

    /** Library detail: statistics plus both rule lists. */
    public record LibraryDetail(LibraryView view, List<FragmentRuleView> fragmentRules,
                                List<ProfileRuleView> profileRules) {
    }

    /** One fragment rule plus the number of nodes it produced. */
    public record FragmentRuleView(MemoryFragmentRule rule, long nodeCount) {
    }

    /** One profile rule plus its parsed field definitions. */
    public record ProfileRuleView(MemoryProfileRule rule, List<MemoryProfileField> fields) {
    }

    /** One memory entity aggregate row. */
    public record EntityView(String userId, long nodeCount, LocalDateTime updatedAt) {
    }

    /** One console page of memory entities. */
    public record EntityPage(List<EntityView> items, int page, int size, long total) {
    }

    /** Validated fragment rule payload from the console. */
    public record FragmentRuleCommand(String name, MemoryInstructionType instructionType,
                                      String instruction, boolean autoUpdate, Integer expireDays,
                                      MemoryExtractVersion extractVersion) {
    }

    /** Validated profile rule payload from the console. */
    public record ProfileRuleCommand(String name, MemoryExtractVersion extractVersion,
                                     List<MemoryProfileField> fields) {
    }
}
