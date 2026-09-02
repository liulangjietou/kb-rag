package io.kbrag.parser.model;

import io.kbrag.parser.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Unified response envelope, shared by both parse endpoints (M1-CONTRACTS.md §5/§6).
 *
 * <p>Success is {@code {code:"OK", data:..., message:"success", request_id}}; failure is
 * {@code {code:"PARSE_FAILED", data:null, message:<reason>, request_id}}. Both are answered with
 * HTTP 200: the envelope carries the outcome, so a parse that failed on the document's own content is
 * not a transport error, and kb-rag-server's client reads {@code code} rather than the status line.
 *
 * @param <T> the payload type, {@link ParseData} or {@link ChatParseData}
 * @author owlzhangfq@gmail.com
 */
@Data
@AllArgsConstructor
public class ApiResponse<T> {

    private String code;

    private T data;

    private String message;

    private String requestId;

    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(ErrorCode.OK, data, "success", requestId);
    }

    public static <T> ApiResponse<T> failed(String message, String requestId) {
        return new ApiResponse<>(ErrorCode.PARSE_FAILED, null, message, requestId);
    }
}
