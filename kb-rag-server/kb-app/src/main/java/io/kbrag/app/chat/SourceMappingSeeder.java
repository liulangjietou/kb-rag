package io.kbrag.app.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.entity.SourceMapping;
import io.kbrag.domain.enums.SourceMappingType;
import io.kbrag.domain.mapper.SourceMappingMapper;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Seeds the built-in chat import mapping profiles on startup.
 *
 * <p>The bodies are verbatim copies of the profiles the parser ships, held here as classpath resources.
 * Copies rather than a shared volume or a call to the parser: the two services are deployed
 * independently and in either order, and a console that could not list the built-in profiles until the
 * parser happened to be reachable would be a startup dependency bought for nothing.
 *
 * <p><b>Insert when absent, never overwrite.</b> That is what makes the pass idempotent across restarts,
 * and it is also what protects the one thing an operator can legitimately do to a built-in row: nothing.
 * Built-in rows are read only through the API, so a body that differs from the resource can only come from
 * a recalibrated release, and the migration that recalibrates it is where that belongs - not in a silent
 * overwrite on every boot.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceMappingSeeder implements ApplicationRunner {

    /** Classpath directory holding the copies of the parser's profile files. */
    private static final String SEED_DIRECTORY = "seed/source-mappings/";

    /**
     * Built-in profiles, declared here rather than discovered by scanning the directory: a file does not
     * say which export format it targets, and deriving that from a file name would make the format a
     * naming convention nobody can see from the console.
     *
     * <p>The names are the parser's own per format defaults, which is what lets an import that names none
     * of them land on the same profile whichever side resolves it. The tabular profile is declared once and
     * serves the spreadsheet upload too: both are read through the same column name candidates.
     */
    private static final List<BuiltinProfile> BUILTIN_PROFILES = List.of(
            new BuiltinProfile("memotrace", SourceMappingType.CSV, "memotrace.yml"),
            new BuiltinProfile("liuhen_txt", SourceMappingType.TXT, "liuhen_txt.yml"),
            new BuiltinProfile("liuhen_html", SourceMappingType.HTML, "liuhen_html.yml"));

    private final SourceMappingMapper sourceMappingMapper;
    private final BizIdGenerator bizIdGenerator;

    @Override
    public void run(ApplicationArguments args) {
        int seeded = 0;
        for (BuiltinProfile profile : BUILTIN_PROFILES) {
            if (exists(profile.name())) {
                continue;
            }
            SourceMapping mapping = new SourceMapping();
            mapping.setMappingId(bizIdGenerator.sourceMappingId());
            mapping.setTenantId(BuiltinTenants.DEFAULT_TENANT_ID);
            mapping.setName(profile.name());
            mapping.setSourceType(profile.sourceType());
            mapping.setProfileYaml(read(profile.resourceName()));
            mapping.setIsBuiltin(SourceMapping.BUILTIN);
            sourceMappingMapper.insert(mapping);
            seeded++;
        }
        log.info("built-in source mappings seeded, declared={}, inserted={}",
                BUILTIN_PROFILES.size(), seeded);
    }

    /**
     * Whether the default tenant already holds this profile.
     *
     * <p><b>The tenant is written by hand on both statements of this class, and that is not optional.</b>
     * A startup runner has no console principal, so {@code KbTenantLineHandler#ignoreTable} skips the
     * fence entirely on this thread - V23 put {@code t_kb_source_mapping} in the fenced set, which
     * covers the console and nothing else. Without the clause below the check would see the copies
     * every other tenant holds and conclude the default tenant is already seeded; without the one in
     * {@link #run}, the insert would fall back to the column DEFAULT, which happens to be the same
     * tenant today and would silently stop being so the day that default changes.
     *
     * @param name profile name
     * @return {@code true} when the default tenant already has a row under that name
     */
    private boolean exists(String name) {
        return sourceMappingMapper.exists(new LambdaQueryWrapper<SourceMapping>()
                .eq(SourceMapping::getTenantId, BuiltinTenants.DEFAULT_TENANT_ID)
                .eq(SourceMapping::getName, name));
    }

    /**
     * Reads one profile body from the classpath.
     *
     * <p>A missing or unreadable resource fails the startup rather than seeding an empty profile: an empty
     * body would be discovered by the first operator who tried to import with it, and the row would then
     * have to be deleted by hand.
     *
     * @param resourceName file name inside the seed directory
     * @return YAML body
     */
    private String read(String resourceName) {
        try (InputStream stream = new ClassPathResource(SEED_DIRECTORY + resourceName).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("read built-in source mapping resource failed, errorCode={}, resource={}",
                    ErrorCode.INTERNAL_ERROR, resourceName, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "built-in source mapping resource is missing: " + resourceName, e);
        }
    }

    /**
     * One declared built-in profile.
     *
     * @param name         profile name, also the legacy {@code mapping_profile} value
     * @param sourceType   export format the profile reads
     * @param resourceName file name inside {@link #SEED_DIRECTORY}
     */
    private record BuiltinProfile(String name, SourceMappingType sourceType, String resourceName) {
    }
}
