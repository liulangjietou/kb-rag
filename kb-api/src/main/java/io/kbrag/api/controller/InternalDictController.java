package io.kbrag.api.controller;

import io.kbrag.app.dict.IkDictService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.DictType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Remote dictionary channel of the ik tokenizer.
 *
 * <p><b>Why this endpoint is unauthenticated.</b> The ik plugin polls the URL from inside
 * Elasticsearch with a bare HTTP client that has no way to carry a bearer token, so requiring one
 * would make the whole hot update mechanism unusable. The exposure is a list of domain terms an
 * operator deliberately entered: no personal data, no document content, no configuration. The path is
 * listed explicitly in the interceptor exclusions of {@code WebMvcConfig} with the same reasoning, so
 * the exemption is visible from both ends.
 *
 * <p><b>The protocol is the caching headers, not the body.</b> The plugin issues a HEAD request,
 * compares {@code Last-Modified} and {@code ETag} with what it saw last time, and only downloads when
 * one of them changed. Answering 304 for an unchanged dictionary is therefore not an optimisation but
 * the mechanism that stops every node from reloading its tokenizer on every poll.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/internal/dict/ik")
@RequiredArgsConstructor
public class InternalDictController {

    /** Plain text, explicitly UTF-8: the plugin does not guess an encoding and would mangle CJK terms. */
    private static final String CONTENT_TYPE = "text/plain; charset=UTF-8";

    /** Resolution of an HTTP date, used to align the stored timestamp with the header. */
    private static final long MILLIS_PER_SECOND = 1000L;

    private final IkDictService ikDictService;

    /**
     * Serves one dictionary.
     *
     * @param type        {@code ext} or {@code stop}
     * @param ifNoneMatch entity tag the caller already holds
     * @param request     servlet request, used to parse the HTTP date of {@code If-Modified-Since}
     * @return dictionary document, or 304 when the caller is already up to date
     */
    @GetMapping("/{type}.txt")
    public ResponseEntity<byte[]> dictionary(
            @PathVariable String type,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request) {
        DictType dictType = DictType.from(type);
        if (dictType == null) {
            throw BizException.notFound("unknown dictionary: " + type);
        }
        IkDictService.DictDocument document = ikDictService.render(dictType);
        String etag = "\"" + document.getEtag() + "\"";

        if (isUpToDate(ifNoneMatch, etag, document.getLastModified(), request)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .lastModified(document.getLastModified())
                    .build();
        }
        byte[] body = document.getBody().getBytes(StandardCharsets.UTF_8);
        log.info("ik dictionary served, dictType={}, words={}, bytes={}",
                dictType.code(), document.getWordCount(), body.length);
        return ResponseEntity.ok()
                .eTag(etag)
                .lastModified(document.getLastModified())
                .contentType(MediaType.parseMediaType(CONTENT_TYPE))
                .body(body);
    }

    /**
     * Decides whether the caller already holds the current dictionary.
     *
     * <p>The entity tag wins whenever the caller sent one, which is not merely the HTTP precedence rule
     * but the only correct answer here: the timestamp is the newest entry of the served set, so removing
     * the most recently edited word makes it move <em>backwards</em>. A caller holding the newer
     * timestamp would then be told it is up to date forever, and its tokenizer would keep a word an
     * operator deliberately deleted. The digest has no such blind spot.
     *
     * @param ifNoneMatch  entity tag the caller holds, {@code null} when it sent none
     * @param etag         entity tag of the current document
     * @param lastModified timestamp of the current document
     * @param request      servlet request, used to parse the HTTP date
     * @return {@code true} when a 304 is the right answer
     */
    private boolean isUpToDate(String ifNoneMatch, String etag, long lastModified,
                               HttpServletRequest request) {
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            return etag.equals(ifNoneMatch.trim());
        }
        // The header carries an HTTP date with second precision, so the stored millisecond timestamp is
        // truncated before the comparison; without it an entry saved mid second always looks newer.
        long ifModifiedSince = request.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE);
        return ifModifiedSince >= 0 && ifModifiedSince >= truncateToSeconds(lastModified);
    }

    private long truncateToSeconds(long epochMillis) {
        return epochMillis - (epochMillis % MILLIS_PER_SECOND);
    }
}
