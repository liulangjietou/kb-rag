package io.kbrag.app.memory;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the console-side invariants of the memory library: the built in rule seeded at creation
 * and shielded from deletion, the 50-rule and 50-field Bailian ceilings, the per-type instruction
 * gate, and the leaf-first cascade that keeps a failed deletion retryable.
 *
 * @author owlzhangfq@gmail.com
 */
class MemoryAdminServiceTest {

    private static final String LIBRARY_ID = "mlib_1";
    private static final String RULE_ID = "mfr_1";

    private MemoryLibraryMapper memoryLibraryMapper;
    private MemoryFragmentRuleMapper memoryFragmentRuleMapper;
    private MemoryProfileRuleMapper memoryProfileRuleMapper;
    private MemoryNodeMapper memoryNodeMapper;
    private MemoryProfileMapper memoryProfileMapper;
    private MemoryAppKeyService memoryAppKeyService;
    private MemoryStore memoryStore;
    private MemoryAdminService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(MemoryLibrary.class);
        MybatisLambdaCache.register(MemoryFragmentRule.class);
        MybatisLambdaCache.register(MemoryProfileRule.class);
        MybatisLambdaCache.register(MemoryNode.class);
        memoryLibraryMapper = mock(MemoryLibraryMapper.class);
        memoryFragmentRuleMapper = mock(MemoryFragmentRuleMapper.class);
        memoryProfileRuleMapper = mock(MemoryProfileRuleMapper.class);
        memoryNodeMapper = mock(MemoryNodeMapper.class);
        memoryProfileMapper = mock(MemoryProfileMapper.class);
        memoryAppKeyService = mock(MemoryAppKeyService.class);
        memoryStore = mock(MemoryStore.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.memoryLibraryId()).thenReturn(LIBRARY_ID);
        when(bizIdGenerator.memoryFragmentRuleId()).thenReturn(RULE_ID);
        when(bizIdGenerator.memoryProfileRuleId()).thenReturn("mpr_1");
        service = new MemoryAdminService(memoryLibraryMapper, memoryFragmentRuleMapper,
                memoryProfileRuleMapper, memoryNodeMapper, memoryProfileMapper,
                memoryAppKeyService, mock(MemoryProfileService.class), mock(MemoryApiService.class),
                memoryStore, bizIdGenerator);
    }

    @Test
    void shouldSeedTheBuiltinDefaultRuleWhenCreatingALibrary() {
        when(memoryLibraryMapper.selectCount(any())).thenReturn(0L);
        stubZeroedStatistics();

        service.createLibrary("客服记忆库", null);

        ArgumentCaptor<MemoryFragmentRule> captor = ArgumentCaptor.forClass(MemoryFragmentRule.class);
        verify(memoryFragmentRuleMapper).insert(captor.capture());
        MemoryFragmentRule seeded = captor.getValue();
        assertEquals("默认项目", seeded.getName());
        assertEquals(MemoryInstructionType.DEFAULT, seeded.getInstructionType());
        assertEquals(1, seeded.getAutoUpdate());
        assertEquals(180, seeded.getExpireDays());
        assertEquals(MemoryExtractVersion.PRO, seeded.getExtractVersion());
        assertEquals(1, seeded.getBuiltin());
    }

    @Test
    void shouldRejectADuplicateLibraryName() {
        when(memoryLibraryMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> service.createLibrary("客服记忆库", null));
        verify(memoryLibraryMapper, never()).insert(any(MemoryLibrary.class));
    }

    @Test
    void shouldCascadeLeafDataFirstAndDropTheLibraryRowLast() {
        // The cascade is deliberately not one transaction; a halfway failure must leave the
        // library row visible so the operator can simply retry - hence the strict ordering.
        when(memoryLibraryMapper.selectOne(any())).thenReturn(libraryRow());

        service.deleteLibrary(LIBRARY_ID);

        InOrder order = inOrder(memoryAppKeyService, memoryFragmentRuleMapper,
                memoryProfileRuleMapper, memoryNodeMapper, memoryProfileMapper, memoryStore,
                memoryLibraryMapper);
        order.verify(memoryAppKeyService).deleteByLibrary(LIBRARY_ID);
        order.verify(memoryFragmentRuleMapper).delete(any());
        order.verify(memoryProfileRuleMapper).delete(any());
        order.verify(memoryNodeMapper).delete(any());
        order.verify(memoryProfileMapper).hardDeleteByLibraryId(LIBRARY_ID);
        order.verify(memoryStore).deleteByLibrary(LIBRARY_ID);
        order.verify(memoryLibraryMapper).deleteById(7L);
    }

    @Test
    void shouldRefuseToDeleteTheBuiltinFragmentRule() {
        MemoryFragmentRule builtin = fragmentRuleRow();
        builtin.setBuiltin(1);
        when(memoryFragmentRuleMapper.selectOne(any())).thenReturn(builtin);

        assertThrows(BizException.class, () -> service.deleteFragmentRule(LIBRARY_ID, RULE_ID));
        verify(memoryNodeMapper, never()).delete(any());
        verify(memoryFragmentRuleMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void shouldCascadeTheRuleMemoriesWhenDeletingACustomRule() {
        when(memoryFragmentRuleMapper.selectOne(any())).thenReturn(fragmentRuleRow());

        service.deleteFragmentRule(LIBRARY_ID, RULE_ID);

        InOrder order = inOrder(memoryNodeMapper, memoryStore, memoryFragmentRuleMapper);
        order.verify(memoryNodeMapper).delete(any());
        order.verify(memoryStore).deleteByRule(LIBRARY_ID, RULE_ID);
        order.verify(memoryFragmentRuleMapper).deleteById(5L);
    }

    @Test
    void shouldEnforceTheFragmentRuleCeiling() {
        when(memoryLibraryMapper.selectOne(any())).thenReturn(libraryRow());
        // First selectCount answers the unique-name probe, the second the ceiling check.
        when(memoryFragmentRuleMapper.selectCount(any())).thenReturn(0L,
                (long) MemoryAdminService.MAX_RULES_PER_LIBRARY);

        assertThrows(BizException.class, () ->
                service.createFragmentRule(LIBRARY_ID, defaultRuleCommand("新规则")));
        verify(memoryFragmentRuleMapper, never()).insert(any(MemoryFragmentRule.class));
    }

    @Test
    void shouldRequireAnInstructionForACustomRule() {
        when(memoryLibraryMapper.selectOne(any())).thenReturn(libraryRow());

        assertThrows(BizException.class, () -> service.createFragmentRule(LIBRARY_ID,
                new MemoryAdminService.FragmentRuleCommand("自定义", MemoryInstructionType.CUSTOM,
                        " ", true, null, null)));
    }

    @Test
    void shouldRejectALifetimeOutsideTheAllowedSet() {
        when(memoryLibraryMapper.selectOne(any())).thenReturn(libraryRow());

        assertThrows(BizException.class, () -> service.createFragmentRule(LIBRARY_ID,
                new MemoryAdminService.FragmentRuleCommand("规则", MemoryInstructionType.DEFAULT,
                        null, true, 15, null)));
    }

    @Test
    void shouldRejectEmptyOrDuplicatedProfileFields() {
        when(memoryLibraryMapper.selectOne(any())).thenReturn(libraryRow());

        assertThrows(BizException.class, () -> service.createProfileRule(LIBRARY_ID,
                new MemoryAdminService.ProfileRuleCommand("画像", null, List.of())));
        assertThrows(BizException.class, () -> service.createProfileRule(LIBRARY_ID,
                new MemoryAdminService.ProfileRuleCommand("画像", null, List.of(
                        new MemoryProfileField("称呼", null, null),
                        new MemoryProfileField("称呼", null, null)))));
        verify(memoryProfileRuleMapper, never()).insert(any(MemoryProfileRule.class));
    }

    @Test
    void shouldAnswerNotFoundForANodeOutsideTheLibrary() {
        when(memoryNodeMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.deleteNode(LIBRARY_ID, "mnode_missing"));
        verify(memoryStore, never()).delete(anyString());
    }

    private void stubZeroedStatistics() {
        when(memoryFragmentRuleMapper.selectCount(any())).thenReturn(0L);
        when(memoryProfileRuleMapper.selectCount(any())).thenReturn(0L);
        when(memoryNodeMapper.selectCount(any())).thenReturn(0L);
        doReturn(List.of(0L)).when(memoryNodeMapper).selectObjs(any());
    }

    private MemoryLibrary libraryRow() {
        MemoryLibrary library = new MemoryLibrary();
        library.setId(7L);
        library.setLibraryId(LIBRARY_ID);
        library.setName("客服记忆库");
        return library;
    }

    private MemoryFragmentRule fragmentRuleRow() {
        MemoryFragmentRule rule = new MemoryFragmentRule();
        rule.setId(5L);
        rule.setRuleId(RULE_ID);
        rule.setLibraryId(LIBRARY_ID);
        rule.setName("自定义规则");
        rule.setInstructionType(MemoryInstructionType.CUSTOM);
        rule.setInstruction("只记录偏好");
        rule.setAutoUpdate(1);
        rule.setExtractVersion(MemoryExtractVersion.PRO);
        rule.setBuiltin(0);
        return rule;
    }

    private MemoryAdminService.FragmentRuleCommand defaultRuleCommand(String name) {
        return new MemoryAdminService.FragmentRuleCommand(name, MemoryInstructionType.DEFAULT,
                null, true, null, MemoryExtractVersion.PRO);
    }
}
