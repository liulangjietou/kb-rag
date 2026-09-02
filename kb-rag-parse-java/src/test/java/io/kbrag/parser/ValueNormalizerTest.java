package io.kbrag.parser;

import io.kbrag.parser.chat.MsgType;
import io.kbrag.parser.chat.ValueNormalizer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Value normalization for raw chat cells (M3-CONTRACTS.md §2.2).
 *
 * @author owlzhangfq@gmail.com
 */
class ValueNormalizerTest {

    @Test
    void epochSecondsAndMillisecondsAreToldApart() {
        assertEquals(1737800000000L, ValueNormalizer.parseSendTimeMs("1737800000"));
        assertEquals(1737800000000L, ValueNormalizer.parseSendTimeMs("1737800000000"));
    }

    @Test
    void nativeDateValuesPassThroughWithoutATextRoundTrip() {
        LocalDateTime moment = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
        long expected = moment.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        assertEquals(expected, ValueNormalizer.parseSendTimeMs(moment));
    }

    @Test
    void commonStringFormatsParse() {
        assertTrue(ValueNormalizer.parseSendTimeMs("2024-01-01 10:00:00") > 0);
        assertTrue(ValueNormalizer.parseSendTimeMs("2024-01-01 10:00") > 0);
        assertTrue(ValueNormalizer.parseSendTimeMs("2024/01/01 10:00:00") > 0);
        assertTrue(ValueNormalizer.parseSendTimeMs("2024-01-01T10:00:00") > 0);
        assertTrue(ValueNormalizer.parseSendTimeMs("2024-01-01") > 0);
    }

    @Test
    void impossibleAndUnknownValuesReturnNull() {
        assertNull(ValueNormalizer.parseSendTimeMs(null));
        assertNull(ValueNormalizer.parseSendTimeMs(""));
        assertNull(ValueNormalizer.parseSendTimeMs("   "));
        assertNull(ValueNormalizer.parseSendTimeMs("not-a-real-timestamp"));
        assertNull(ValueNormalizer.parseSendTimeMs("2024-13-99 99:99:99"));
    }

    @Test
    void javaOnlyNumericLiteralsAreNotMistakenForTimestamps() {
        // Double.parseDouble would happily read these; Python's float() would not, and a profile
        // written against one service must not behave differently on the other.
        assertNull(ValueNormalizer.parseSendTimeMs("0x1p3"));
        assertNull(ValueNormalizer.parseSendTimeMs("1737800000d"));
        assertNull(ValueNormalizer.parseSendTimeMs("Infinity"));
    }

    @Test
    void isSelfCoercionAcceptsTheDocumentedTokensOnly() {
        assertTrue(ValueNormalizer.coerceIsSelf("1"));
        assertTrue(ValueNormalizer.coerceIsSelf("TRUE"));
        assertTrue(ValueNormalizer.coerceIsSelf("是"));
        assertFalse(ValueNormalizer.coerceIsSelf("0"));
        assertFalse(ValueNormalizer.coerceIsSelf(null));
        assertFalse(ValueNormalizer.coerceIsSelf("maybe"));
    }

    @Test
    void msgTypeBucketsTextualLabels() {
        assertEquals(MsgType.VOICE, ValueNormalizer.classifyMsgType("语音消息"));
        assertEquals(MsgType.VIDEO, ValueNormalizer.classifyMsgType("Video"));
        assertEquals(MsgType.IMAGE, ValueNormalizer.classifyMsgType("图片"));
        assertEquals(MsgType.TEXT, ValueNormalizer.classifyMsgType("text"));
    }

    @Test
    void msgTypeDoesNotMatchAnAsciiTokenInsideALongerWord() {
        // "text" inside "context" must not classify the message as text - the whole reason the ASCII
        // path uses word boundaries while the CJK path uses a substring.
        assertEquals(MsgType.OTHER, ValueNormalizer.classifyMsgType("context"));
    }

    @Test
    void msgTypeBucketsNumericCodes() {
        assertEquals(MsgType.TEXT, ValueNormalizer.classifyMsgType("1"));
        assertEquals(MsgType.IMAGE, ValueNormalizer.classifyMsgType("3"));
        assertEquals(MsgType.VOICE, ValueNormalizer.classifyMsgType("34"));
        assertEquals(MsgType.VIDEO, ValueNormalizer.classifyMsgType("43"));
        assertEquals(MsgType.VIDEO, ValueNormalizer.classifyMsgType("62"));
        assertEquals(MsgType.OTHER, ValueNormalizer.classifyMsgType("49"));
    }

    @Test
    void aMissingTypeColumnMeansPlainText() {
        // An export with no type column at all holds plain text messages, not unclassifiable ones.
        assertEquals(MsgType.TEXT, ValueNormalizer.classifyMsgType(null));
        assertEquals(MsgType.TEXT, ValueNormalizer.classifyMsgType("  "));
    }
}
