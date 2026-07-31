package io.kbrag.app.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.MemoryProfile;
import io.kbrag.domain.entity.MemoryProfileRule;
import io.kbrag.domain.mapper.MemoryProfileMapper;
import io.kbrag.domain.mapper.MemoryProfileRuleMapper;
import io.kbrag.domain.model.MemoryProfileField;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User profile reads and writes, the M19 contract.
 *
 * <p><b>Merge, never replace.</b> One conversation rarely supports every field of a rule, so an
 * extraction writes only the attributes it found over the ones already stored. The stored JSON
 * therefore only ever grows more complete, and a field the model never filled stays absent - which
 * is what lets the read side fall back to the rule's initial value without ambiguity.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryProfileService {

    private final MemoryProfileRuleMapper memoryProfileRuleMapper;
    private final MemoryProfileMapper memoryProfileMapper;

    /**
     * Loads a profile rule of one library or fails.
     *
     * @param libraryId library business id, the ownership predicate
     * @param ruleId    profile rule business id
     * @return rule row
     */
    public MemoryProfileRule requireRule(String libraryId, String ruleId) {
        MemoryProfileRule rule = memoryProfileRuleMapper.selectOne(
                new LambdaQueryWrapper<MemoryProfileRule>()
                        .eq(MemoryProfileRule::getLibraryId, libraryId)
                        .eq(MemoryProfileRule::getRuleId, ruleId)
                        .last("limit 1"));
        if (rule == null) {
            throw BizException.notFound("profile rule not found");
        }
        return rule;
    }

    /**
     * Parses the field definitions of a rule.
     *
     * @param rule profile rule row
     * @return attribute definitions, empty when the column is blank
     */
    public List<MemoryProfileField> fieldsOf(MemoryProfileRule rule) {
        if (rule.getFields() == null || rule.getFields().isBlank()) {
            return List.of();
        }
        List<MemoryProfileField> fields = JsonUtil.parse(rule.getFields(),
                new TypeReference<List<MemoryProfileField>>() {
                });
        return fields == null ? List.of() : fields;
    }

    /**
     * Merges newly extracted attributes into the entity's stored profile.
     *
     * @param rule      profile rule the attributes were extracted under
     * @param userId    memory entity id
     * @param extracted validated attributes, no-op when empty
     */
    @Transactional(rollbackFor = Exception.class)
    public void merge(MemoryProfileRule rule, String userId, Map<String, String> extracted) {
        if (extracted == null || extracted.isEmpty()) {
            return;
        }
        MemoryProfile stored = find(rule.getRuleId(), userId);
        if (stored == null) {
            MemoryProfile profile = new MemoryProfile();
            profile.setLibraryId(rule.getLibraryId());
            profile.setRuleId(rule.getRuleId());
            profile.setUserId(userId);
            profile.setAttributes(JsonUtil.toJson(extracted));
            memoryProfileMapper.insert(profile);
        } else {
            Map<String, String> merged = new LinkedHashMap<>(attributesOf(stored));
            merged.putAll(extracted);
            stored.setAttributes(JsonUtil.toJson(merged));
            memoryProfileMapper.updateById(stored);
        }
        log.info("memory profile merged, ruleId={}, attributeCount={}",
                rule.getRuleId(), extracted.size());
    }

    /**
     * Builds the profile view of one entity under one rule: every field the rule defines, valued
     * from the stored extraction, falling back to the rule's initial value, then {@code null}.
     *
     * @param rule   profile rule row
     * @param userId memory entity id
     * @return profile view, present even when nothing was extracted yet
     */
    public ProfileView viewOf(MemoryProfileRule rule, String userId) {
        MemoryProfile stored = find(rule.getRuleId(), userId);
        Map<String, String> extracted = stored == null ? Map.of() : attributesOf(stored);
        List<AttributeValue> attributes = new ArrayList<>();
        for (MemoryProfileField field : fieldsOf(rule)) {
            String value = extracted.get(field.name());
            if (value == null) {
                value = field.initialValue() == null || field.initialValue().isBlank()
                        ? null : field.initialValue();
            }
            attributes.add(new AttributeValue(field.name(), value));
        }
        return new ProfileView(rule.getRuleId(), rule.getName(), userId, attributes,
                stored == null ? null : stored.getUpdatedAt());
    }

    /**
     * Builds the profile views of one entity across the library's rules.
     *
     * @param libraryId library business id
     * @param userId    memory entity id
     * @param ruleId    restricts to one rule, {@code null} for all
     * @return profile views in rule creation order
     */
    public List<ProfileView> viewsOf(String libraryId, String userId, String ruleId) {
        if (ruleId != null && !ruleId.isBlank()) {
            return List.of(viewOf(requireRule(libraryId, ruleId), userId));
        }
        List<MemoryProfileRule> rules = memoryProfileRuleMapper.selectList(
                new LambdaQueryWrapper<MemoryProfileRule>()
                        .eq(MemoryProfileRule::getLibraryId, libraryId)
                        .orderByAsc(MemoryProfileRule::getId));
        List<ProfileView> views = new ArrayList<>(rules.size());
        for (MemoryProfileRule rule : rules) {
            views.add(viewOf(rule, userId));
        }
        return views;
    }

    private MemoryProfile find(String ruleId, String userId) {
        return memoryProfileMapper.selectOne(new LambdaQueryWrapper<MemoryProfile>()
                .eq(MemoryProfile::getRuleId, ruleId)
                .eq(MemoryProfile::getUserId, userId)
                .last("limit 1"));
    }

    private Map<String, String> attributesOf(MemoryProfile profile) {
        if (profile.getAttributes() == null || profile.getAttributes().isBlank()) {
            return Map.of();
        }
        Map<String, String> attributes = JsonUtil.parse(profile.getAttributes(),
                new TypeReference<LinkedHashMap<String, String>>() {
                });
        return attributes == null ? Map.of() : attributes;
    }

    /**
     * One attribute of a profile view.
     *
     * @param name  attribute name
     * @param value current value, {@code null} when neither extracted nor given an initial value
     */
    public record AttributeValue(String name, String value) {
    }

    /**
     * The profile of one entity under one rule, ready for transport mapping.
     *
     * @param ruleId     profile rule business id
     * @param ruleName   profile rule display name
     * @param userId     memory entity id
     * @param attributes every field the rule defines with its current value
     * @param updatedAt  last extraction write, {@code null} when nothing was extracted yet
     */
    public record ProfileView(String ruleId, String ruleName, String userId,
                              List<AttributeValue> attributes, LocalDateTime updatedAt) {
    }
}
