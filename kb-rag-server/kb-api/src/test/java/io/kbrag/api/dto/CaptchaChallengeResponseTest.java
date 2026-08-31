package io.kbrag.api.dto;

import io.kbrag.common.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化拼图 challenge 的公开字段，避免把服务端横向答案误放进响应。
 *
 * @author owlzhangfq@gmail.com
 */
class CaptchaChallengeResponseTest {

    @Test
    void shouldExposeImagesAndGeometryWithoutNumericTarget() {
        CaptchaChallengeResponse response = new CaptchaChallengeResponse(
                "challenge", 1_000, 120,
                "data:image/png;base64,background", "data:image/png;base64,piece",
                320, 160, 48, 48, 56);

        Set<String> components = Arrays.stream(CaptchaChallengeResponse.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        assertEquals(Set.of("challengeId", "trackScale", "expiresInSeconds",
                "backgroundImage", "pieceImage", "imageWidth", "imageHeight",
                "pieceWidth", "pieceHeight", "pieceY"), components);

        String json = JsonUtil.toJson(response);
        assertTrue(json.contains("\"background_image\""));
        assertTrue(json.contains("\"piece_image\""));
        assertTrue(json.contains("\"piece_y\""));
        assertFalse(json.contains("target"));
        assertFalse(json.contains("gap_x"));
    }
}
