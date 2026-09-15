package io.kbrag.api.advice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 类型转换错误返回稳定输入错误，不能把原始参数或转换堆栈记入日志。 */
class InvalidRequestParameterTest {
    @Test
    void shouldReturnBadRequestWithoutLoggingRawParameterForJsonAndStreamingAccepts() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (String accept : new String[]{MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE}) {
                var result = mvc.perform(get("/parameter-probe").param("page", "private-marker").accept(accept))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.code").value("INVALID_PARAM")).andReturn();
                assertFalse(result.getResponse().getContentAsString().contains("private-marker"));
            }
            assertFalse(appender.list.isEmpty());
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
        @GetMapping("/parameter-probe")
        public int receive(@RequestParam int page) { return page; }
    }
}
