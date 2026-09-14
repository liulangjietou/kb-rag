package io.kbrag.api.controller;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.appcenter.AppVersionComparisonService;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.AppCorpusDocument;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 MVC 路由、权限、分页绑定及最小响应投影，不读取或变更业务数据。 */
class AppVersionComparisonContractTest {
    private final AppVersionComparisonService service = mock(AppVersionComparisonService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AppVersionComparisonController(service))
                .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
        bind(Set.of(PermissionCodes.APP_READ));
    }

    @AfterEach
    void tearDown() { UserContextHolder.clear(); }

    @Test
    void comparisonBindsBaselineAndPageAndNeverReturnsInternalDocumentFields() throws Exception {
        var empty = new AppVersionComparisonService.CorpusSummary(AppVersionComparisonService.Source.EMPTY, true, 0);
        var target = new AppVersionComparisonService.CorpusSummary(AppVersionComparisonService.Source.FROZEN, true, 1);
        var doc = new AppCorpusDocument("dv", "doc", "资料.md", "2.0");
        when(service.compareCorpus("candidate", "baseline", 2, 10)).thenReturn(new AppVersionComparisonService.Comparison(
                empty, target, true, 1L, 1L, 0L, 0L, 2, 10, List.of(new AppVersionComparisonService.DocumentChange(
                        "kb", "doc", "资料.md", AppVersionComparisonService.Change.ADDED, null, doc))));
        mvc.perform(get("/api/v1/app-versions/candidate/corpus-diff").param("baseline_id", "baseline").param("page", "2").param("page_size", "10"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.page_size").value(10))
                .andExpect(jsonPath("$.data.candidate.document_count").value(1))
                .andExpect(jsonPath("$.data.items[0].doc_id").value("doc"))
                .andExpect(jsonPath("$.data.items[0].candidate.version_id").value("dv"))
                .andExpect(jsonPath("$.data.items[0].candidate.docId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].candidate.fileName").doesNotExist());
        verify(service).compareCorpus("candidate", "baseline", 2, 10);
    }

    @Test
    void rejectsInvalidPagingOrBlankBaselineAtTheBoundary() throws Exception {
        mvc.perform(get("/api/v1/app-versions/v/corpus-diff").param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/app-versions/v/corpus-diff").param("page_size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/app-versions/v/corpus-diff").param("baseline_id", " ")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void knowledgeBaseReadAloneDoesNotPermitApplicationComparison() throws Exception {
        bind(Set.of(PermissionCodes.KB_READ));
        mvc.perform(get("/api/v1/app-versions/v/corpus-diff")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private void bind(Set<String> permissions) {
        UserContextHolder.set(new UserPrincipal("user", "tenant", "user", "User", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, true, Set.of(), true, Set.of()));
    }
}
