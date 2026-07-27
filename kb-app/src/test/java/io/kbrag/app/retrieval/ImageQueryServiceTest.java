package io.kbrag.app.retrieval;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.port.VisionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the image query stage of requirement section 4.8: the ceilings that bound one call, the shape of
 * the concatenated question, and the all or nothing degradation - including the case where dropping the
 * images leaves nothing to search for at all.
 *
 * @author owlzhangfq@gmail.com
 */
class ImageQueryServiceTest {

    private static final String QUERY = "这个零件的价格是多少";

    private VisionProvider visionProvider;
    private KbProperties properties;
    private ImageQueryService service;

    @BeforeEach
    void setUp() {
        visionProvider = mock(VisionProvider.class);
        properties = new KbProperties();
        when(visionProvider.isConfigured()).thenReturn(true);
        service = new ImageQueryService(visionProvider, properties);
    }

    @Test
    void shouldLeaveAPlainTextQueryUntouched() {
        ImageQueryService.ImageQueryOutcome outcome = service.enrich(QUERY, List.of());

        assertEquals(QUERY, outcome.query());
        assertEquals(List.of(), outcome.degraded());
        verify(visionProvider, never()).describeImage(any(), anyString());
    }

    @Test
    void shouldAppendTheImageTextToTheTailOfTheQuery() {
        when(visionProvider.describeImage(any(), anyString())).thenReturn("一个金属法兰盘的照片");

        ImageQueryService.ImageQueryOutcome outcome = service.enrich(QUERY, List.of(image(16)));

        assertEquals(QUERY + "\n" + ImageQueryService.IMAGE_TEXT_PREFIX + "一个金属法兰盘的照片",
                outcome.query());
        assertEquals(List.of(), outcome.degraded());
    }

    @Test
    void shouldPrefixEveryImageSeparately() {
        when(visionProvider.describeImage(any(), anyString())).thenReturn("第一张", "第二张");

        ImageQueryService.ImageQueryOutcome outcome =
                service.enrich(QUERY, List.of(image(16), image(32)));

        assertEquals(QUERY + "\n" + ImageQueryService.IMAGE_TEXT_PREFIX + "第一张"
                + "\n" + ImageQueryService.IMAGE_TEXT_PREFIX + "第二张", outcome.query());
    }

    @Test
    void shouldServeAnImageOnlyQuestionOnceTheImageIsUnderstood() {
        when(visionProvider.describeImage(any(), anyString())).thenReturn("一个金属法兰盘的照片");

        ImageQueryService.ImageQueryOutcome outcome = service.enrich("  ", List.of(image(16)));

        assertEquals(ImageQueryService.IMAGE_TEXT_PREFIX + "一个金属法兰盘的照片", outcome.query());
    }

    @Test
    void shouldRejectMoreImagesThanTheConfiguredCount() {
        List<String> images = new ArrayList<>();
        for (int i = 0; i < properties.getRetrieval().getImageQueryMaxCount() + 1; i++) {
            images.add(image(16));
        }

        BizException failure =
                assertThrows(BizException.class, () -> service.enrich(QUERY, images));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        verify(visionProvider, never()).describeImage(any(), anyString());
    }

    @Test
    void shouldRejectASingleImageOverTheByteCeiling() {
        properties.getRetrieval().setImageQueryMaxBytes(64);

        BizException failure =
                assertThrows(BizException.class, () -> service.enrich(QUERY, List.of(image(65))));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
    }

    @Test
    void shouldRejectImagesThatExceedTheTotalCeilingTogether() {
        properties.getRetrieval().setImageQueryMaxBytes(100);
        properties.getRetrieval().setImageQueryMaxTotalBytes(150);

        BizException failure = assertThrows(BizException.class,
                () -> service.enrich(QUERY, List.of(image(100), image(100))));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
    }

    @Test
    void shouldRejectAPayloadThatIsNotBase64() {
        BizException failure =
                assertThrows(BizException.class, () -> service.enrich(QUERY, List.of("不是 base64!!")));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
    }

    @Test
    void shouldRejectACallThatCarriesNeitherAQuestionNorAnImage() {
        BizException failure = assertThrows(BizException.class, () -> service.enrich("   ", List.of()));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
    }

    @Test
    void shouldDegradeToTheWrittenQuestionInAZeroKeyDeployment() {
        when(visionProvider.isConfigured()).thenReturn(false);

        ImageQueryService.ImageQueryOutcome outcome = service.enrich(QUERY, List.of(image(16)));

        assertEquals(QUERY, outcome.query());
        assertEquals(List.of(DegradedReason.IMAGE_UNDERSTANDING_UNAVAILABLE.code()), outcome.degraded());
        verify(visionProvider, never()).describeImage(any(), anyString());
    }

    @Test
    void shouldDropEveryImageWhenOneOfThemFails() {
        when(visionProvider.describeImage(any(), anyString()))
                .thenReturn("第一张")
                .thenThrow(new IllegalStateException("provider down"));

        ImageQueryService.ImageQueryOutcome outcome =
                service.enrich(QUERY, List.of(image(16), image(32)));

        // Half understood image sets produce results nobody can explain, so the understood one goes too.
        assertEquals(QUERY, outcome.query());
        assertEquals(List.of(DegradedReason.IMAGE_UNDERSTANDING_UNAVAILABLE.code()), outcome.degraded());
    }

    @Test
    void shouldDegradeWhenTheProviderAnswersWithNoText() {
        when(visionProvider.describeImage(any(), anyString())).thenReturn("   ");

        ImageQueryService.ImageQueryOutcome outcome = service.enrich(QUERY, List.of(image(16)));

        assertEquals(QUERY, outcome.query());
        assertEquals(List.of(DegradedReason.IMAGE_UNDERSTANDING_UNAVAILABLE.code()), outcome.degraded());
    }

    @Test
    void shouldRejectAnImageOnlyQuestionWhoseImageCouldNotBeUnderstood() {
        when(visionProvider.isConfigured()).thenReturn(false);

        BizException failure =
                assertThrows(BizException.class, () -> service.enrich(null, List.of(image(16))));

        // Nothing is left to search for, and a blank query would recall an arbitrary slice of the corpus.
        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
    }

    @Test
    void shouldAcceptAPayloadThatStillCarriesItsDataUrlHeader() {
        when(visionProvider.describeImage(any(), anyString())).thenReturn("一个金属法兰盘的照片");

        ImageQueryService.ImageQueryOutcome outcome =
                service.enrich(QUERY, List.of("data:image/png;base64," + image(16)));

        assertTrue(outcome.query().contains(ImageQueryService.IMAGE_TEXT_PREFIX));
        assertEquals(List.of(), outcome.degraded());
    }

    /**
     * Base64 of an arbitrary payload of the requested decoded size.
     *
     * @param bytes decoded size in bytes
     * @return base64 payload
     */
    private String image(int bytes) {
        byte[] content = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            content[i] = (byte) i;
        }
        return Base64.getEncoder().encodeToString(content);
    }
}
