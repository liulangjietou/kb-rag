package io.kbrag.common.api;

import lombok.Getter;

/**
 * Unified error codes exposed through the REST layer.
 *
 * <p>The M1 subset is fixed by the delivery contract; every code carries the HTTP status the
 * API layer must answer with, so controllers never hard code numeric literals.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
public enum ErrorCode {

    /** Successful invocation. */
    OK(200, "success"),

    /** Request payload failed the single fast-fail validation gate at the controller layer. */
    INVALID_PARAM(400, "invalid parameter"),

    /** Missing, expired or malformed bearer token. */
    UNAUTHORIZED(401, "unauthorized"),

    /** Referenced business resource does not exist or was soft deleted. */
    NOT_FOUND(404, "resource not found"),

    /** Document parsing failed inside the parser service or during post processing. */
    PARSE_FAILED(500, "parse document failed"),

    /** Upstream model provider rejected or could not serve the request. */
    UPSTREAM_MODEL_ERROR(502, "upstream model error"),

    /** Unclassified server side failure. */
    INTERNAL_ERROR(500, "internal error");

    /** HTTP status mapped to this business code. */
    private final int httpStatus;

    /** Default, implementation agnostic message. */
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
