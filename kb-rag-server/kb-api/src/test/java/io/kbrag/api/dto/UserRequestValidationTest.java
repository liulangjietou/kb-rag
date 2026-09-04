package io.kbrag.api.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化人工创建和编辑账号时对 RFC 最大长度邮箱的统一接纳边界。
 *
 * @author owlzhangfq@gmail.com
 */
class UserRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptAValid254CharacterEmailForCreateAndUpdate() {
        String email = validEmailWithLength(254);

        assertTrue(validator.validate(new CreateUserRequest(
                "person", "Person", email, "Password!1", List.of(), null)).isEmpty());
        assertTrue(validator.validate(new UpdateUserRequest("Person", email)).isEmpty());
    }

    @Test
    void shouldRejectAnEmailLongerThan254Characters() {
        String email = validEmailWithLength(254) + "x";

        assertFalse(validator.validate(new CreateUserRequest(
                "person", "Person", email, "Password!1", List.of(), null)).isEmpty());
        assertFalse(validator.validate(new UpdateUserRequest("Person", email)).isEmpty());
    }

    private String validEmailWithLength(int length) {
        String prefix = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + ".";
        return prefix + "d".repeat(length - prefix.length());
    }
}
