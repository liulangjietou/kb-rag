package io.kbrag.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.document.DocumentAclService;
import io.kbrag.app.document.DocumentListFilter;
import io.kbrag.app.document.DocumentPreviewService;
import io.kbrag.app.document.DocumentService;
import io.kbrag.app.governance.DocumentGovernanceService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.PublishStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 MVC 参数绑定、权限拦截和时间契约；查询效果由应用层真实 SQL 测试覆盖。 */
class DocumentListContractTest {
    private final DocumentService documents = mock(DocumentService.class);
    private final KbResourceGuard guard = mock(KbResourceGuard.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(documents,
                        mock(DocumentPreviewService.class), mock(DocumentGovernanceService.class),
                        mock(DocumentAclService.class), guard))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        bind(Set.of(PermissionCodes.KB_READ));
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void shouldBindCombinedFiltersAndExposeActualUpdateTime() throws Exception {
        LocalDateTime from = LocalDateTime.parse("2026-09-01T00:00:00");
        LocalDateTime to = LocalDateTime.parse("2026-09-11T23:59:59.999");
        Document document = new Document();
        document.setDocId("doc_1");
        document.setProcessStatus(ProcessStatus.INDEXED);
        document.setUpdatedAt(to);
        when(documents.list(eq("kb_1"), any(), anyLong(), anyLong()))
                .thenReturn(new Page<Document>(2, 10, 11).setRecords(List.of(document)));
        mvc.perform(get("/api/v1/kb/kb_1/documents")
                        .param("keyword", " 报销%_! ").param("process_status", "INDEXED")
                        .param("publish_status", "published").param("source", "WEB")
                        .param("updated_from", from.toString()).param("updated_to", to.toString())
                        .param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.items[0].updated_at").value(to.toString()));
        verify(guard).requireKb("kb_1");
        verify(documents).list("kb_1", new DocumentListFilter("报销%_!", ProcessStatus.INDEXED,
                PublishStatus.PUBLISHED, DocumentListFilter.Source.WEB, from, to), 2, 10);
    }

    @ParameterizedTest
    @CsvSource({"source,PRIVATE", "publish_status,UNKNOWN", "process_status,UNKNOWN",
            "updated_from,2026-02-30T12:00:00", "updated_to,2026-09-01"})
    void shouldRejectInvalidFiltersBeforeQuery(String field, String value) throws Exception {
        mvc.perform(get("/api/v1/kb/kb_1/documents").param(field, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        verifyNoInteractions(documents);
    }

    @Test
    void shouldPreservePermissionAndKbBoundaries() throws Exception {
        bind(Set.of());
        mvc.perform(get("/api/v1/kb/kb_1/documents")).andExpect(status().isForbidden());
        verifyNoInteractions(guard, documents);
        bind(Set.of(PermissionCodes.KB_READ));
        doThrow(BizException.notFound("knowledge base not found")).when(guard).requireKb("kb_1");
        mvc.perform(get("/api/v1/kb/kb_1/documents").param("keyword", "秘密"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(documents);
    }

    private void bind(Set<String> permissions) {
        UserContextHolder.set(new UserPrincipal("usr_1", "tnt_1", "tester", "Tester", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, true, Set.of()));
    }
}
