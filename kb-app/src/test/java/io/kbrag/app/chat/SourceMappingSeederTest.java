package io.kbrag.app.chat;

import io.kbrag.domain.entity.SourceMapping;
import io.kbrag.domain.enums.SourceMappingType;
import io.kbrag.domain.mapper.SourceMappingMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the seeding pass: what it declares, that it never overwrites, and that a restart on a seeded
 * database writes nothing.
 *
 * @author owlzhangfq@gmail.com
 */
class SourceMappingSeederTest {

    private SourceMappingMapper sourceMappingMapper;
    private SourceMappingSeeder seeder;

    @BeforeEach
    void setUp() {
        sourceMappingMapper = mock(SourceMappingMapper.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.sourceMappingId()).thenReturn("smp_seeded");
        seeder = new SourceMappingSeeder(sourceMappingMapper, bizIdGenerator);
    }

    @Test
    void shouldSeedTheBuiltinProfilesOfEveryFormatOnAnEmptyDatabase() {
        when(sourceMappingMapper.exists(any())).thenReturn(false);

        seeder.run(null);

        List<SourceMapping> seeded = captureInserted(3);
        assertEquals(List.of("memotrace", "liuhen_txt", "liuhen_html"),
                seeded.stream().map(SourceMapping::getName).toList());
        // The names are the parser's own per format defaults, so an import that names none of them lands on
        // the same profile whichever side resolves it.
        assertEquals(List.of(SourceMappingType.CSV, SourceMappingType.TXT, SourceMappingType.HTML),
                seeded.stream().map(SourceMapping::getSourceType).toList());
        assertTrue(seeded.stream().allMatch(SourceMapping::builtin));
    }

    @Test
    void shouldSeedTheProfileBodiesFromTheClasspath() {
        when(sourceMappingMapper.exists(any())).thenReturn(false);

        seeder.run(null);

        List<SourceMapping> seeded = captureInserted(3);
        // The bodies are the parser's schema, not a placeholder: the tabular profile names columns, the
        // transcript profile carries named group patterns and the HTML profile carries selectors.
        assertTrue(seeded.get(0).getProfileYaml().contains("session_id:"));
        assertTrue(seeded.get(1).getProfileYaml().contains("(?P<send_time>"));
        assertTrue(seeded.get(1).getProfileYaml().contains("(?P<sender>"));
        assertTrue(seeded.get(2).getProfileYaml().contains("message:"));
    }

    @Test
    void shouldWriteNothingOnARestart() {
        when(sourceMappingMapper.exists(any())).thenReturn(true);

        seeder.run(null);

        // Insert when absent, never overwrite: that is what makes the pass idempotent, and it is also what
        // keeps a recalibrated body from being reverted on every boot.
        verify(sourceMappingMapper, never()).insert(any(SourceMapping.class));
    }

    @Test
    void shouldSeedOnlyTheProfilesThatAreMissing() {
        when(sourceMappingMapper.exists(any())).thenReturn(true, false, false);

        seeder.run(null);

        List<SourceMapping> seeded = captureInserted(2);
        assertEquals(List.of("liuhen_txt", "liuhen_html"),
                seeded.stream().map(SourceMapping::getName).toList());
    }

    private List<SourceMapping> captureInserted(int expected) {
        ArgumentCaptor<SourceMapping> captor = ArgumentCaptor.forClass(SourceMapping.class);
        verify(sourceMappingMapper, times(expected)).insert(captor.capture());
        return captor.getAllValues();
    }
}
