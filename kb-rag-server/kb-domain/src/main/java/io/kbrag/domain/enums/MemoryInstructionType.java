package io.kbrag.domain.enums;

/**
 * Extraction instruction source of a memory fragment rule, the M19 contract.
 *
 * <p>DEFAULT keeps the built in extraction prompt maintained by this system, CUSTOM replaces it with
 * an operator written one stored on the rule. Kept as an explicit type rather than "instruction is
 * blank means default" so a rule that clears its custom text is a deliberate state change, not an
 * accident that silently swaps prompts.
 *
 * @author owlzhangfq@gmail.com
 */
public enum MemoryInstructionType {

    /** Built in extraction instruction shipped with the system. */
    DEFAULT,

    /** Operator written instruction stored on the rule, mandatory when this type is chosen. */
    CUSTOM;

    /**
     * Resolves a type from its literal, case insensitively.
     *
     * @param value request literal
     * @return matching type
     * @throws IllegalArgumentException when the literal matches no type
     */
    public static MemoryInstructionType from(String value) {
        if (value != null) {
            for (MemoryInstructionType type : values()) {
                if (type.name().equalsIgnoreCase(value.trim())) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("unknown memory instruction type: " + value);
    }
}
