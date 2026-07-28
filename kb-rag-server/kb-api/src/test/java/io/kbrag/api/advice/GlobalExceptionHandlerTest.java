package io.kbrag.api.advice;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.UnsupportedEncodingException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming branch of the error envelope: a request that only accepts {@code text/event-stream}
 * must still receive its business status and JSON body instead of the bare 500 that content
 * negotiation failure used to produce (an expired console token on the SSE chat preview surfaced as
 * "Internal Server Error" with no way for the client to react).
 *
 * @author owlzhangfq@gmail.com
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldWriteTheEnvelopeByHandForAStreamOnlyAcceptHeader() throws UnsupportedEncodingException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Result<Void>> entity =
                handler.handleBiz(BizException.unauthorized("token expired or invalid"), request, response);

        assertNull(entity);
        assertEquals(ErrorCode.UNAUTHORIZED.getHttpStatus(), response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
    }

    @Test
    void shouldKeepTheNegotiatedPathForARegularJsonRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept", "application/json, text/plain, */*");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Result<Void>> entity =
                handler.handleBiz(BizException.invalidParam("bad input"), request, response);

        assertNotNull(entity);
        assertEquals(ErrorCode.INVALID_PARAM.getHttpStatus(), entity.getStatusCode().value());
    }

    @Test
    void shouldTreatAWildcardAcceptAsNegotiable() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept", "text/event-stream, */*");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Result<Void>> entity =
                handler.handleBiz(BizException.invalidParam("bad input"), request, response);

        assertNotNull(entity);
    }

    @Test
    void shouldAnswerAnUnknownPathWithNotFoundRatherThanInternalError() {
        ResponseEntity<Result<Void>> entity = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.POST, "api/v1/knowledge/search/api/v1/knowledge/search"));

        assertNotNull(entity);
        assertEquals(ErrorCode.NOT_FOUND.getHttpStatus(), entity.getStatusCode().value());
        assertNotNull(entity.getBody());
        assertEquals(ErrorCode.NOT_FOUND.name(), entity.getBody().getCode());
        // 报文里回显路径，调用方一眼能看出是自己把 baseURL 和路径拼了两遍
        assertTrue(entity.getBody().getMessage().contains("api/v1/knowledge/search/api/v1/knowledge/search"));
    }

    @Test
    void shouldWriteTheEnvelopeByHandForUnexpectedFailuresOnAStream() throws UnsupportedEncodingException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Result<Void>> entity =
                handler.handleUnexpected(new IllegalStateException("boom"), request, response);

        assertNull(entity);
        assertEquals(500, response.getStatus());
        assertTrue(response.getContentAsString().contains("INTERNAL_ERROR"));
    }
}
