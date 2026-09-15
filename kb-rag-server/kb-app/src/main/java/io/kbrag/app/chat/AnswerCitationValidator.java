package io.kbrag.app.chat;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 校验生成内容的引用契约；不改写答案，也不推断某段证据是否支持结论。 */
final class AnswerCitationValidator {
    private static final Parser MARKDOWN = Parser.builder().build();
    private static final Pattern CITATION = Pattern.compile("\\[([0-9]+)\\]");
    private static final int MAX_INDEX_DIGITS = 9;

    private AnswerCitationValidator() { }

    /** 仅检查 Markdown 正文；代码、链接和图片内容不是本轮引用控件。 */
    static void validate(String answer, int referenceCount) {
        if (answer == null || answer.isEmpty()) return;
        MARKDOWN.parse(answer).accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                Matcher matcher = CITATION.matcher(text.getLiteral());
                while (matcher.find()) {
                    String digits = matcher.group(1);
                    if (digits.length() > MAX_INDEX_DIGITS) throw invalidCitation();
                    int index = Integer.parseInt(digits);
                    if (index < 1 || index > referenceCount) throw invalidCitation();
                }
            }

            @Override
            public void visit(Link link) { /* 普通 Markdown 链接不转换为本轮引用。 */ }

            @Override
            public void visit(Image image) { /* 图片替代文本不属于回答引用。 */ }
        });
    }

    private static BizException invalidCitation() {
        return new BizException(ErrorCode.ANSWER_CITATION_INVALID,
                "回答引用校验未通过，当前内容仅供核查，请重新生成");
    }
}
