package io.kbrag.api.controller;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.appcenter.AppPreviewCatalogService;
import io.kbrag.app.appcenter.AppService;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.openapi.ChatStreamListener;
import io.kbrag.app.openapi.KnowledgeApiService;
import io.kbrag.app.openapi.KnowledgeCallResult;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实 MVC 绑定和权限拦截器验证目录、版本 ID 与两种传输的契约。 */
class AppPreviewContractTest {

    private final AppPreviewCatalogService catalog = mock(AppPreviewCatalogService.class);
    private final AppService apps = mock(AppService.class);
    private final KnowledgeApiService knowledge = mock(KnowledgeApiService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new AppPreviewCatalogController(catalog),
                        new AppController(apps, mock(AppVersionService.class), knowledge))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        bind(Set.of(PermissionCodes.SEARCH_DEBUG));
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void shouldAllowDebugCatalogWithoutExposingManagementOrConfiguration() throws Exception {
        App app = new App();
        app.setAppId("app_1");
        app.setName("知识助手");
        AppVersion version = new AppVersion();
        version.setAppVersionId("av_1");
        version.setVersion("v1");
        version.setStatus(AppVersionStatus.DRAFT);
        version.setConfig("{\"prompt\":\"private\"}");
        when(catalog.list()).thenReturn(List.of(new AppPreviewCatalogService.PreviewApplication(app, List.of(version))));

        mvc.perform(get("/api/v1/app-previews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].app_id").value("app_1"))
                .andExpect(jsonPath("$.data[0].versions[0].app_version_id").value("av_1"))
                .andExpect(jsonPath("$.data[0].versions[0].config").doesNotExist());
        mvc.perform(get("/api/v1/apps")).andExpect(status().isForbidden());
        verifyNoInteractions(apps);
    }

    @Test
    void shouldDenyCatalogWhenNeitherPreviewPermissionIsPresent() throws Exception {
        bind(Set.of(PermissionCodes.KB_READ));
        mvc.perform(get("/api/v1/app-previews")).andExpect(status().isForbidden());
        verifyNoInteractions(catalog);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldBindSelectedVersionIdForBothTransports(boolean stream) throws Exception {
        when(knowledge.preview(eq("app_1"), eq("av_selected"), any(), isNull()))
                .thenReturn(KnowledgeCallResult.builder().nodes(List.of()).degraded(List.of()).answer("回答").build());
        doAnswer(invocation -> {
            ChatStreamListener listener = invocation.getArgument(3);
            listener.onDone("req_1", List.of(), List.of());
            return null;
        }).when(knowledge).previewStreamAsync(eq("app_1"), eq("av_selected"), any(), any());

        mvc.perform(post("/api/v1/apps/app_1/chat-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"问题\",\"app_version_id\":\"av_selected\",\"stream\":" + stream + "}"))
                .andExpect(status().isOk());

        verify(knowledge).requirePreviewKbAccess("app_1", "av_selected");
        if (stream) {
            verify(knowledge).previewStreamAsync(eq("app_1"), eq("av_selected"), any(), any());
        } else {
            verify(knowledge).preview(eq("app_1"), eq("av_selected"), any(), isNull());
        }
    }

    private void bind(Set<String> permissions) {
        UserContextHolder.set(new UserPrincipal("usr_1", "tnt_1", "tester", "Tester", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, true, Set.of()));
    }
}
