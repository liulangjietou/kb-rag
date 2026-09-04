package io.kbrag.app.registration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 固化注册 HMAC 的算法、编码和域分隔向量。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationHmacTest {

    @Test
    void shouldMatchFixedVerificationCodeAndSourceIpVectors() {
        RegistrationProperties properties = new RegistrationProperties();
        properties.setCodeHmacKey("0123456789abcdef0123456789abcdef");
        RegistrationHmac hmac = new RegistrationHmac(properties);

        String code = hmac.verificationCode(
                "evf_fixed", "person@example.com", "123456");
        String sourceIp = hmac.sourceIp("203.0.113.9");

        assertEquals("247c35d7b7b0efd9827d4339d491ca6287cf0d894c6fbe1ab63204c9c99f1ca9",
                code);
        assertEquals("4ee34739ecf3891ecfa3ec80011433b5c30916ef5b76ddcdb19f25f403a2410e",
                sourceIp);
        assertNotEquals(code, sourceIp);
    }
}
