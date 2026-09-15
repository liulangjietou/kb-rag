package io.kbrag.api.controller;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.quality.KnowledgeQualityIssueService;
import io.kbrag.app.quality.QualityRegressionVerifier;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实 MVC 拦截器与参数绑定，避免服务单测掩盖入口权限或字段泄露。 */
class QualityIssueHttpContractTest {
    private static final String ROOT = "/api/v1/kb/kb/quality-issues";
    private final KnowledgeQualityIssueService service = mock(KnowledgeQualityIssueService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeQualityIssueController(service))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    @Test
    void shouldRequireFeedbackManagementEvenWhenEvaluationPermissionsArePresent() throws Exception {
        mvc.perform(get(ROOT + "/issue")).andExpect(status().isUnauthorized());
        bind("eval:read", "eval:write", "app:read");
        mvc.perform(get(ROOT + "/issue")).andExpect(status().isForbidden());
        mvc.perform(post(ROOT + "/issue/claim").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void shouldProjectOwnershipWithoutExposingZeroHitHashOrInternalEvidenceList() throws Exception {
        bind("feedback:manage");
        var issue = KnowledgeQualityIssue.open("issue", "kb", QualityIssueSource.ZERO_HIT, "private-hash", "脱敏摘要");
        issue.claim("user", "处理人", 0);
        issue.setProtectedDocIds("[\"private-doc\"]");
        when(service.detail("kb", "issue")).thenReturn(new KnowledgeQualityIssueService.Detail(issue, null));
        mvc.perform(get(ROOT + "/issue")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.owned_by_me").value(true))
                .andExpect(jsonPath("$.data.source_id").doesNotExist())
                .andExpect(jsonPath("$.data.protected_doc_ids").doesNotExist())
                .andExpect(jsonPath("$.data.expected_case_input").doesNotExist());
    }

    @Test
    void shouldRejectInvalidMutationBodiesBeforeTheService() throws Exception {
        bind("feedback:manage");
        for (String body : List.of("{}", "{\"revision\":null}", "{\"revision\":-1}")) {
            mvc.perform(post(ROOT + "/issue/claim").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        for (String body : List.of("{\"source_type\":\"UNKNOWN\",\"source_id\":\"id\"}",
                "{\"source_type\":\"ZERO_HIT\",\"source_id\":\" \"}")) {
            mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(ROOT + "/issue/notes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0,\"note\":\"" + "长".repeat(2049) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectInvalidQueryTypesBeforeTheService() throws Exception {
        bind("feedback:manage");
        for (String query : List.of("status=UNKNOWN", "page=not-a-number", "mine=perhaps", "size=99999999999999999999999")) {
            mvc.perform(get(ROOT + "?" + query)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturnConflictWithoutCachingStaleState() throws Exception {
        bind("feedback:manage");
        when(service.claim("kb", "issue", 0))
                .thenThrow(new BizException(ErrorCode.QUALITY_ISSUE_CONFLICT, "请读取最新记录"));
        mvc.perform(post(ROOT + "/issue/claim").contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
                .andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("QUALITY_ISSUE_CONFLICT"));
    }

    @Test
    void shouldOnlyProjectTheVerifiedCaseAndNeverItsProtectedDocumentIds() throws Exception {
        bind("feedback:manage");
        EvalResult result = new EvalResult();
        result.setCaseId("case");
        result.setGeneratedAnswer("已核验的答案");
        when(service.previewRegression("kb", "issue", "run"))
                .thenReturn(new QualityRegressionVerifier.Verification("version", result, Set.of("protected-doc")));
        mvc.perform(get(ROOT + "/issue/regressions/run")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.result.case_id").value("case"))
                .andExpect(jsonPath("$.data.result.generated_answer").value("已核验的答案"))
                .andExpect(jsonPath("$.data.minimum_dimension_score").value(4))
                .andExpect(jsonPath("$.data.protected_doc_ids").doesNotExist());
    }

    private void bind(String... permissions) {
        UserContextHolder.set(new UserPrincipal("user", "tenant", "operator", "处理人", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permissions), true, Set.of(), true, Set.of()));
    }
}
