package io.kbrag.parser.support;

import java.math.BigDecimal;

/**
 * Cell-value stringification shared by the xlsx document parser and the xlsx chat reader.
 *
 * <p>Exists to keep the Java output identical to the Python service's, which renders every openpyxl
 * cell through {@code str(value)}. POI hands back every numeric cell as a {@code double}, so a naive
 * {@code String.valueOf} would turn the age column {@code 30} into {@code "30.0"} - a difference that
 * reaches the retrieval corpus and every markdown table in it.
 *
 * @author owlzhangfq@gmail.com
 */
public final class NumberFormatting {

    private NumberFormatting() {
    }

    /**
     * Renders a numeric cell the way the Python service does: whole numbers without a decimal point,
     * fractions in plain (non-scientific) notation.
     *
     * @param value numeric cell value
     * @return its string form
     */
    public static String formatNumeric(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
