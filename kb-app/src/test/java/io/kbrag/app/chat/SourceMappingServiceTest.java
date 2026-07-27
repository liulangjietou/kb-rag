package io.kbrag.app.chat;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.SourceMapping;
import io.kbrag.domain.enums.SourceMappingType;
import io.kbrag.domain.mapper.SourceMappingMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the mapping profile maintenance rules: the name uniqueness the import resolution depends on, the
 * read only guarantee of a seeded template, the copy that makes one editable, and the per format default
 * an import falls back to.
 *
 * @author owlzhangfq@gmail.com
 */
class SourceMappingServiceTest {

    private static final String BUILTIN_NAME = "memotrace";
    private static final String BUILTIN_ID = "smp_builtin";
    private static final String CUSTOM_ID = "smp_custom";
    private static final String NEW_ID = "smp_new";
    private static final String YAML = "session_id:\n  - talker\n";

    private SourceMappingMapper sourceMappingMapper;
    private SourceMappingService service;

    @BeforeEach
    void setUp() {
        sourceMappingMapper = mock(SourceMappingMapper.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.sourceMappingId()).thenReturn(NEW_ID);
        service = new SourceMappingService(sourceMappingMapper, bizIdGenerator);
    }

    @Test
    void shouldCreateACustomProfile() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(null);

        service.create("my_export", SourceMappingType.TXT, YAML);

        SourceMapping saved = captureInserted();
        assertEquals(NEW_ID, saved.getMappingId());
        assertEquals("my_export", saved.getName());
        assertEquals(SourceMappingType.TXT, saved.getSourceType());
        assertEquals(YAML, saved.getProfileYaml());
        assertEquals(SourceMapping.CUSTOM, saved.getIsBuiltin());
        assertFalse(saved.builtin());
    }

    @Test
    void shouldTrimTheNameBeforeStoringIt() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(null);

        service.create("  my_export  ", SourceMappingType.TXT, YAML);

        assertEquals("my_export", captureInserted().getName());
    }

    @Test
    void shouldRejectADuplicateName() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(builtin());

        BizException thrown = assertThrows(BizException.class,
                () -> service.create(BUILTIN_NAME, SourceMappingType.CSV, YAML));

        // The name is how an import addresses a profile, so a second row holding it would leave the
        // resolution to pick between them arbitrarily.
        assertEquals(ErrorCode.INVALID_PARAM, thrown.getErrorCode());
        verify(sourceMappingMapper, never()).insert(any(SourceMapping.class));
    }

    @Test
    void shouldRejectABlankName() {
        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(BizException.class,
                () -> service.create("  ", SourceMappingType.CSV, YAML)).getErrorCode());
    }

    @Test
    void shouldRefuseToEditABuiltinProfile() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(builtin());

        BizException thrown = assertThrows(BizException.class,
                () -> service.update(BUILTIN_ID, "renamed", SourceMappingType.CSV, YAML));

        assertEquals(ErrorCode.INVALID_PARAM, thrown.getErrorCode());
        verify(sourceMappingMapper, never()).updateById(any(SourceMapping.class));
    }

    @Test
    void shouldRefuseToDeleteABuiltinProfile() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(builtin());

        BizException thrown = assertThrows(BizException.class, () -> service.delete(BUILTIN_ID));

        assertEquals(ErrorCode.INVALID_PARAM, thrown.getErrorCode());
        verify(sourceMappingMapper, never()).deleteById(anyLong());
    }

    @Test
    void shouldReplaceACustomProfileInFull() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(custom(), (SourceMapping) null);

        service.update(CUSTOM_ID, "renamed", SourceMappingType.HTML, "html:\n  message: div\n");

        ArgumentCaptor<SourceMapping> captor = ArgumentCaptor.forClass(SourceMapping.class);
        verify(sourceMappingMapper).updateById(captor.capture());
        assertEquals("renamed", captor.getValue().getName());
        assertEquals(SourceMappingType.HTML, captor.getValue().getSourceType());
        assertEquals("html:\n  message: div\n", captor.getValue().getProfileYaml());
    }

    @Test
    void shouldAllowAnUpdateThatKeepsTheCurrentName() {
        // Only one lookup happens: the name did not change, so the uniqueness check would otherwise find
        // the row it is about to update and refuse it.
        when(sourceMappingMapper.selectOne(any())).thenReturn(custom());

        service.update(CUSTOM_ID, "my_export", SourceMappingType.CSV, YAML);

        verify(sourceMappingMapper).updateById(any(SourceMapping.class));
    }

    @Test
    void shouldDeleteACustomProfile() {
        SourceMapping custom = custom();
        when(sourceMappingMapper.selectOne(any())).thenReturn(custom);

        service.delete(CUSTOM_ID);

        verify(sourceMappingMapper).deleteById(custom.getId());
    }

    @Test
    void shouldReportAMissingProfileAsNotFound() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(null);

        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(BizException.class, () -> service.require("smp_absent")).getErrorCode());
    }

    @Test
    void shouldCopyABuiltinIntoACustomRow() {
        // require -> the builtin; the generated name check and the uniqueness check both find nothing.
        when(sourceMappingMapper.selectOne(any())).thenReturn(builtin(), null, null);

        service.copy(BUILTIN_ID, null);

        SourceMapping saved = captureInserted();
        assertEquals("副本-" + BUILTIN_NAME, saved.getName());
        assertEquals(SourceMapping.CUSTOM, saved.getIsBuiltin());
        assertEquals(SourceMappingType.CSV, saved.getSourceType());
        // The body is carried over verbatim: the copy exists so an operator can tune it, not restart it.
        assertEquals(YAML, saved.getProfileYaml());
        assertEquals(NEW_ID, saved.getMappingId());
    }

    @Test
    void shouldNumberTheSecondGeneratedCopyName() {
        // The plain generated name is taken; copying the same template twice is a normal console gesture.
        when(sourceMappingMapper.selectOne(any())).thenReturn(builtin(), custom(), null, null);

        service.copy(BUILTIN_ID, null);

        assertEquals("副本-" + BUILTIN_NAME + "-2", captureInserted().getName());
    }

    @Test
    void shouldHonourAnExplicitCopyName() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(builtin(), null);

        service.copy(BUILTIN_ID, "my_tuned_export");

        assertEquals("my_tuned_export", captureInserted().getName());
    }

    @Test
    void shouldRejectACopyNameAlreadyTaken() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(builtin(), custom());

        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(BizException.class,
                () -> service.copy(BUILTIN_ID, "my_export")).getErrorCode());
    }

    @Test
    void shouldResolveAProfileByItsBusinessId() {
        SourceMapping builtin = builtin();
        when(sourceMappingMapper.selectOne(any())).thenReturn(builtin);

        assertSame(builtin, service.findByIdOrName(BUILTIN_ID));
    }

    @Test
    void shouldFallBackToTheNameWhenTheValueIsNotABusinessId() {
        SourceMapping builtin = builtin();
        when(sourceMappingMapper.selectOne(any())).thenReturn(null, builtin);

        // The legacy import parameter carried the profile name, so an import script written before this
        // table existed must keep resolving.
        assertSame(builtin, service.findByIdOrName(BUILTIN_NAME));
    }

    @Test
    void shouldResolveNothingForABlankValue() {
        assertNull(service.findByIdOrName(null));
        assertNull(service.findByIdOrName("  "));
        verify(sourceMappingMapper, never()).selectOne(any());
    }

    @Test
    void shouldUseTheConfiguredDefaultWhenItReadsTheUploadedFormat() {
        SourceMapping builtin = builtin();
        when(sourceMappingMapper.selectOne(any())).thenReturn(null, builtin);

        assertSame(builtin, service.defaultFor(SourceMappingType.CSV, BUILTIN_NAME));
    }

    @Test
    void shouldUseTheConfiguredTabularDefaultForASpreadsheet() {
        SourceMapping builtin = builtin();
        when(sourceMappingMapper.selectOne(any())).thenReturn(null, builtin);

        // One tabular profile serves both: the columns are the same whether they arrived delimited or in
        // a sheet, so refusing the pair would force two byte identical rows to exist.
        assertSame(builtin, service.defaultFor(SourceMappingType.XLSX, BUILTIN_NAME));
    }

    @Test
    void shouldFallBackToTheBuiltinOfTheUploadedFormat() {
        SourceMapping txtBuiltin = builtin();
        txtBuiltin.setSourceType(SourceMappingType.TXT);
        when(sourceMappingMapper.selectOne(any())).thenReturn(null, builtin(), txtBuiltin);

        // The deployment default is tabular while the upload is a transcript: that is a format the default
        // was never about, so the built-in profile of the uploaded format takes over.
        assertSame(txtBuiltin, service.defaultFor(SourceMappingType.TXT, BUILTIN_NAME));
    }

    @Test
    void shouldResolveNoDefaultWhenNothingReadsTheFormat() {
        when(sourceMappingMapper.selectOne(any())).thenReturn(null, null, null);

        assertNull(service.defaultFor(SourceMappingType.HTML, BUILTIN_NAME));
    }

    private SourceMapping captureInserted() {
        ArgumentCaptor<SourceMapping> captor = ArgumentCaptor.forClass(SourceMapping.class);
        verify(sourceMappingMapper).insert(captor.capture());
        return captor.getValue();
    }

    private SourceMapping builtin() {
        SourceMapping mapping = new SourceMapping();
        mapping.setId(1L);
        mapping.setMappingId(BUILTIN_ID);
        mapping.setName(BUILTIN_NAME);
        mapping.setSourceType(SourceMappingType.CSV);
        mapping.setProfileYaml(YAML);
        mapping.setIsBuiltin(SourceMapping.BUILTIN);
        return mapping;
    }

    private SourceMapping custom() {
        SourceMapping mapping = new SourceMapping();
        mapping.setId(2L);
        mapping.setMappingId(CUSTOM_ID);
        mapping.setName("my_export");
        mapping.setSourceType(SourceMappingType.CSV);
        mapping.setProfileYaml(YAML);
        mapping.setIsBuiltin(SourceMapping.CUSTOM);
        return mapping;
    }
}
