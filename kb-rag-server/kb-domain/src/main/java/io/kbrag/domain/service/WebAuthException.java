package io.kbrag.domain.service;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;

/**
 * A fetch was rejected by the remote site's authentication, the M18 contract.
 *
 * <p>A dedicated type rather than a message convention, because the sync pass changes behaviour on
 * it: after one authentication rejection of a host, the remaining sources of the same host in the
 * same pass are skipped without a request. Sites such as Confluence lock an account behind a
 * CAPTCHA after a handful of failed attempts - a nightly pass hammering fifty URLs with a rotated
 * password would lock the account for good, so one failure per host per pass is the ceiling.
 *
 * @author owlzhangfq@gmail.com
 */
public class WebAuthException extends BizException {

    private static final long serialVersionUID = 1L;

    public WebAuthException(String message) {
        super(ErrorCode.INTERNAL_ERROR, message);
    }
}
