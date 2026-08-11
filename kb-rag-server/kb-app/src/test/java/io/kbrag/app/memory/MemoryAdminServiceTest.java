package io.kbrag.app.memory;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.api.ErrorCode;
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
import org.junit.jupiter.api.function.Executable;
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
 * gate, the leaf-first cascade that keeps a failed deletion retryable, and the tenant boundary -
 * every entry resolves its library first, so a library the fence filters away ends the call before
 * any subordinate statement runs.
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
        // The real guard over the mocked mapper, so a library the tenant fence filters away is
        // simply a null row here - which is exactly what the fence does on a console thread.
        service = new MemoryAdminService(new MemoryLibraryGuard(memoryLibraryMapper),
                memoryLibraryMapper, memoryFragmentRuleMapper,
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
        when(memoryLibraryMapper.selectOne(any())).thenReturn(libraryRow());
        when(memoryFragmentRuleMapper.selectOne(any())).thenReturn(builtin);

        assertThrows(BizException.class, () -> service.deleteFragmentRule(LIBRARY_ID, RULE_ID));
        verify(memoryNodeMapper, never()).delete(any());
        verify(memoryFragmentRuleMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void shouldCascadeTheRuleMemoriesWhenDeletingACustomRule() {
        when(memoryLibraryMapper.selectOne(any())).thenReturn(libraryRow());
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
        when(memoryLibraryMapper.selectOne(any())).thenReturn(libraryRow());
        when(memoryNodeMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.deleteNode(LIBRARY_ID, "mnode_missing"));
        verify(memoryStore, never()).delete(anyString());
    }

    @Test
    void shouldRefuseEveryRuleAndNodeEntryOfALibraryOutsideTheTenant() {
        // The tenant fence trims the library statement to the caller's tenant, so a library of
        // another tenant comes back null. These five entries address a rule or a node directly and
        // used to reach their subordinate statement without ever touching the library table - which
        // is precisely how a foreign tenant could edit rules and delete memories it named by id.
        when(memoryLibraryMapper.selectOne(any())).thenReturn(null);

        // 404 rather than 403 is the contract: answering "forbidden" would confirm to another
        // tenant that the library exists.
        assertNotFound(() -> service.updateFragmentRule(LIBRARY_ID, RULE_ID, defaultRuleCommand("改名")));
        assertNotFound(() -> service.deleteFragmentRule(LIBRARY_ID, RULE_ID));
        assertNotFound(() -> service.updateProfileRule(LIBRARY_ID, "mpr_1",
                new MemoryAdminService.ProfileRuleCommand("画像", null,
                        List.of(new MemoryProfileField("称呼", null, null)))));
        assertNotFound(() -> service.deleteProfileRule(LIBRARY_ID, "mpr_1"));
        assertNotFound(() -> service.deleteNode(LIBRARY_ID, "mnode_1"));

        // Nothing was read from or written to the subordinate tables: the call ends at the library.
        verify(memoryFragmentRuleMapper, never()).selectOne(any());
        verify(memoryProfileRuleMapper, never()).selectOne(any());
        verify(memoryNodeMapper, never()).selectOne(any());
        verify(memoryProfileMapper, never()).hardDeleteByRuleId(anyString());
        verify(memoryStore, never()).delete(anyString());
        verify(memoryStore, never()).deleteByRule(anyString(), anyString());
    }

    @Test
    void shouldRefuseEveryReadEntryOfALibraryOutsideTheTenant() {
        when(memoryLibraryMapper.selectOne(any())).thenReturn(null);

        assertNotFound(() -> service.libraryDetail(LIBRARY_ID));
        assertNotFound(() -> service.listFragmentRules(LIBRARY_ID));
        assertNotFound(() -> service.listProfileRules(LIBRARY_ID));
        assertNotFound(() -> service.pageEntities(LIBRARY_ID, null, 1, 20));
        assertNotFound(() -> service.pageNodes(LIBRARY_ID, "u1", null, 1, 20));
        assertNotFound(() -> service.profiles(LIBRARY_ID, "u1", null));
        assertNotFound(() -> service.deleteLibrary(LIBRARY_ID));

        verify(memoryLibraryMapper, never()).deleteById(any(Long.class));
    }

    private void assertNotFound(Executable call) {
        BizException e = assertThrows(BizException.class, call);
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
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
