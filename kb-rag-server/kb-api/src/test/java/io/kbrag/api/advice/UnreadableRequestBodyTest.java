package io.kbrag.api.advice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kbrag.domain.enums.FeedbackVerdict;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JSON 绑定失败属于输入错误，异常原因中的原始字段值不能进入日志或错误响应。 */
class UnreadableRequestBodyTest {
    @Test
    void shouldRejectInvalidJsonAndFieldTypesForJsonAndStreamingAccepts() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (String accept : new String[] {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE}) {
                for (String body : new String[] {"{", "{\"verdict\":\"private-marker\"}", "[]", ""}) {
                    var result = mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).accept(accept).content(body))
                            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                            .andExpect(jsonPath("$.code").value("INVALID_PARAM")).andReturn();
                    assertFalse(result.getResponse().getContentAsString().contains("private-marker"));
                }
            }
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("private-marker"));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @RestController
    static class Probe {
        @PostMapping("/probe")
        public Body receive(@RequestBody Body body) { return body; }
    }

    record Body(FeedbackVerdict verdict) { }
}
