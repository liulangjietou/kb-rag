package io.kbrag.app.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.SourceMapping;
import io.kbrag.domain.enums.SourceMappingType;
import io.kbrag.domain.mapper.SourceMappingMapper;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Maintenance of the chat import mapping profiles.
 *
 * <p>Why the profiles moved from files into a table. A new export format is a mapping question, not a code
 * question: the parser already resolves columns, line templates and node selectors from a profile, so the
 * only thing standing between an operator and a new format was that the profile lived in a file inside the
 * parser image. Holding it in MySQL and shipping it with every parse call turns that into a console edit.
 *
 * <p><b>Built-in rows are copied, never edited.</b> They are the templates a later release recalibrates
 * against real export samples, and an in place edit would be reverted by that recalibration without anyone
 * noticing. {@link #copy} is therefore the only way a built-in profile becomes editable, and the copy is a
 * custom row that no seeding pass will ever touch.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceMappingService {

    /** Prefix of the name generated when a copy is requested without one. */
    private static final String COPY_NAME_PREFIX = "副本-";

    /** Separator before the disambiguating number of a generated copy name. */
    private static final String COPY_NAME_SUFFIX_SEPARATOR = "-";

    /** First number appended when the plain generated copy name is taken. */
    private static final int FIRST_COPY_SUFFIX = 2;

    /** Attempts made to find a free generated copy name before giving up. */
    private static final int MAX_COPY_ATTEMPTS = 50;

    private final SourceMappingMapper sourceMappingMapper;
    private final BizIdGenerator bizIdGenerator;

    /**
     * Lists the profiles, built-in ones first and alphabetical inside each group.
     *
     * <p>Unpaged on purpose: the list is the set of export formats a deployment can read, which is a
     * handful of rows, and a page cursor would only make the console's dropdown harder to fill.
     *
     * @param sourceType optional export format filter
     * @return profiles in display order
     */
    public List<SourceMapping> list(SourceMappingType sourceType) {
        LambdaQueryWrapper<SourceMapping> wrapper = new LambdaQueryWrapper<SourceMapping>()
                .orderByDesc(SourceMapping::getIsBuiltin)
                .orderByAsc(SourceMapping::getName);
        if (sourceType != null) {
            wrapper.eq(SourceMapping::getSourceType, sourceType);
        }
        return sourceMappingMapper.selectList(wrapper);
    }

    /**
     * Returns one profile.
     *
     * @param mappingId business identifier
     * @return profile row
     */
    public SourceMapping require(String mappingId) {
        SourceMapping mapping = findByMappingId(mappingId);
        if (mapping == null) {
            throw BizException.notFound("source mapping not found: " + mappingId);
        }
        return mapping;
    }

    /**
     * Resolves the profile an import parameter names.
     *
     * <p>Accepts a business id or a name, which is what keeps an import written against a built-in profile
     * name working after the profiles moved into this table.
     *
     * @param value business identifier or profile name, blank yielding nothing
     * @return matching profile, {@code null} when the value matches neither
     */
    public SourceMapping findByIdOrName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        SourceMapping byId = findByMappingId(trimmed);
        return byId != null ? byId : findByName(trimmed);
    }

    /**
     * Resolves the profile an import should use when the caller named none.
     *
     * <p>The deployment default is a single name while the uploaded formats are four, so it can only be
     * the default of the format it actually reads. A transcript uploaded into a deployment whose default is
     * the tabular profile is not a mistake anybody made - it is a format that default was never about - so
     * the built-in profile of the uploaded format takes over instead of the import failing.
     *
     * @param uploaded          format of the uploaded file
     * @param configuredDefault deployment default profile name
     * @return profile to use, {@code null} when nothing stored reads the format
     */
    public SourceMapping defaultFor(SourceMappingType uploaded, String configuredDefault) {
        SourceMapping configured = findByIdOrName(configuredDefault);
        if (configured != null && configured.getSourceType().reads(uploaded)) {
            return configured;
        }
        if (uploaded == null) {
            return null;
        }
        return sourceMappingMapper.selectOne(new LambdaQueryWrapper<SourceMapping>()
                .eq(SourceMapping::getSourceType, uploaded)
                .eq(SourceMapping::getIsBuiltin, SourceMapping.BUILTIN)
                .orderByAsc(SourceMapping::getName)
                .last("limit 1"));
    }

    /**
     * Creates a custom profile.
     *
     * @param name        profile name, unique across every row
     * @param sourceType  export format
     * @param profileYaml full YAML body
     * @return created profile
     */
    public SourceMapping create(String name, SourceMappingType sourceType, String profileYaml) {
        String normalized = requireName(name);
        requireNameFree(normalized);
        SourceMapping mapping = new SourceMapping();
        mapping.setMappingId(bizIdGenerator.sourceMappingId());
        mapping.setName(normalized);
        mapping.setSourceType(sourceType);
        mapping.setProfileYaml(profileYaml);
        mapping.setIsBuiltin(SourceMapping.CUSTOM);
        sourceMappingMapper.insert(mapping);
        log.info("source mapping created, mappingId={}, name={}, sourceType={}",
                mapping.getMappingId(), normalized, sourceType.code());
        return mapping;
    }

    /**
     * Replaces a custom profile in full.
     *
     * <p>A full replacement rather than a patch: the console edits the YAML in a text area and sends back
     * the whole row, and a partial update would leave the name, the format and the body able to disagree
     * about which export the profile describes.
     *
     * @param mappingId   business identifier
     * @param name        new profile name
     * @param sourceType  new export format
     * @param profileYaml new YAML body
     * @return updated profile
     */
    public SourceMapping update(String mappingId, String name, SourceMappingType sourceType,
                                String profileYaml) {
        SourceMapping mapping = requireCustom(mappingId, "edited");
        String normalized = requireName(name);
        if (!normalized.equals(mapping.getName())) {
            requireNameFree(normalized);
        }
        mapping.setName(normalized);
        mapping.setSourceType(sourceType);
        mapping.setProfileYaml(profileYaml);
        sourceMappingMapper.updateById(mapping);
        log.info("source mapping updated, mappingId={}, name={}, sourceType={}",
                mappingId, normalized, sourceType.code());
        return mapping;
    }

    /**
     * Copies any profile into a new custom one.
     *
     * @param mappingId business identifier of the profile being copied
     * @param name      name of the copy, {@code null} generating one from the source name
     * @return created profile
     */
    public SourceMapping copy(String mappingId, String name) {
        SourceMapping source = require(mappingId);
        String resolved = name == null || name.isBlank() ? generateCopyName(source.getName())
                : requireName(name);
        requireNameFree(resolved);
        SourceMapping copy = new SourceMapping();
        copy.setMappingId(bizIdGenerator.sourceMappingId());
        copy.setName(resolved);
        copy.setSourceType(source.getSourceType());
        copy.setProfileYaml(source.getProfileYaml());
        copy.setIsBuiltin(SourceMapping.CUSTOM);
        sourceMappingMapper.insert(copy);
        log.info("source mapping copied, sourceMappingId={}, mappingId={}, name={}",
                mappingId, copy.getMappingId(), resolved);
        return copy;
    }

    /**
     * Removes a custom profile.
     *
     * @param mappingId business identifier
     */
    public void delete(String mappingId) {
        SourceMapping mapping = requireCustom(mappingId, "deleted");
        sourceMappingMapper.deleteById(mapping.getId());
        log.info("source mapping deleted, mappingId={}, name={}", mappingId, mapping.getName());
    }

    /**
     * Loads a profile and refuses the operation when it is a seeded template.
     *
     * @param mappingId business identifier
     * @param operation verb naming the refused operation, for the error message
     * @return custom profile row
     */
    private SourceMapping requireCustom(String mappingId, String operation) {
        SourceMapping mapping = require(mappingId);
        if (mapping.builtin()) {
            throw BizException.invalidParam(
                    "a built-in source mapping cannot be " + operation + ", copy it first: " + mappingId);
        }
        return mapping;
    }

    /**
     * Generates a free name for a copy.
     *
     * @param sourceName name of the profile being copied
     * @return name no row holds yet
     */
    private String generateCopyName(String sourceName) {
        String base = COPY_NAME_PREFIX + sourceName;
        if (findByName(base) == null) {
            return base;
        }
        // Copying the same template twice is a normal console gesture, so the second copy is numbered
        // instead of being refused for a name the operator never typed.
        for (int suffix = FIRST_COPY_SUFFIX; suffix < FIRST_COPY_SUFFIX + MAX_COPY_ATTEMPTS; suffix++) {
            String candidate = base + COPY_NAME_SUFFIX_SEPARATOR + suffix;
            if (findByName(candidate) == null) {
                return candidate;
            }
        }
        throw BizException.invalidParam("too many copies of " + sourceName + ", supply a name");
    }

    private void requireNameFree(String name) {
        if (findByName(name) != null) {
            throw BizException.invalidParam("source mapping name already exists: " + name);
        }
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw BizException.invalidParam("source mapping name is required");
        }
        return name.trim();
    }

    private SourceMapping findByMappingId(String mappingId) {
        return sourceMappingMapper.selectOne(new LambdaQueryWrapper<SourceMapping>()
                .eq(SourceMapping::getMappingId, mappingId)
                .last("limit 1"));
    }

    private SourceMapping findByName(String name) {
        return sourceMappingMapper.selectOne(new LambdaQueryWrapper<SourceMapping>()
                .eq(SourceMapping::getName, name)
                .last("limit 1"));
    }
}
