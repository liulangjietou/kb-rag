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

    /**
     * Authenticated console user lacks the permission code, or the knowledge base is outside the data
     * scope of every role it holds.
     *
     * <p>Distinct from {@link #UNAUTHORIZED} on purpose: the console must not bounce an authenticated
     * user back to the login page for an operation their account is simply not allowed to perform.
     */
    FORBIDDEN(403, "permission denied"),

    /** Referenced business resource does not exist or was soft deleted. */
    NOT_FOUND(404, "resource not found"),

    /** Open API key is unknown, malformed or does not match any stored hash. */
    INVALID_API_KEY(401, "invalid api key"),

    /** Open API key exists but an operator disabled it. */
    API_KEY_DISABLED(401, "api key disabled"),

    /** Open API key is valid but its authorisation scope excludes the requested application. */
    APP_ACCESS_DENIED(403, "application access denied"),

    /** Requested application does not exist. */
    APP_NOT_FOUND(404, "application not found"),

    /** Requested application version does not exist, or is retired and therefore no longer callable. */
    VERSION_NOT_FOUND(404, "application version not found"),

    /**
     * Requested application version exists but has not reached a callable state; distinct from
     * {@link #VERSION_NOT_FOUND} on purpose, so a caller can tell "wrong id" from "not released yet".
     */
    VERSION_NOT_PUBLISHED(409, "application version not published"),

    /** Per key token bucket exhausted; the response carries a {@code Retry-After} header. */
    RATE_LIMITED(429, "rate limit exceeded"),

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
