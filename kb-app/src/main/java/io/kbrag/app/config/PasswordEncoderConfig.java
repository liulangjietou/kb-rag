package io.kbrag.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Password hashing configuration.
 *
 * <p>Only the BCrypt encoder of Spring Security is pulled in, not the whole security filter chain:
 * the console uses an opaque header token verified by a single interceptor, so a full framework
 * integration would add configuration surface without adding protection.
 */
@Configuration
public class PasswordEncoderConfig {

    /** BCrypt cost factor, ten is the Spring Security default and a sane CPU trade off. */
    private static final int BCRYPT_STRENGTH = 10;

    /**
     * Creates the shared password encoder.
     *
     * @return BCrypt encoder
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}
