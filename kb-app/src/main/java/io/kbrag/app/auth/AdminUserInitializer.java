package io.kbrag.app.auth;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the bootstrap administrator on an empty database.
 *
 * <p>No fixed default password is shipped: a random one is generated, printed once to the startup
 * log and flagged as mandatory to rotate. A hard coded default would be the single most exploited
 * weakness of a self hosted deployment.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements ApplicationRunner {

    /** Entropy of the generated bootstrap password in bytes. */
    private static final int PASSWORD_BYTES = 12;

    private static final int MUST_CHANGE = 1;

    private final AdminUserMapper adminUserMapper;
    private final KbProperties properties;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        Long existing = adminUserMapper.selectCount(null);
        if (existing != null && existing > 0) {
            log.info("administrator account already present, skipping bootstrap");
            return;
        }
        String username = properties.getAuth().getBootstrapUsername();
        String password = generatePassword();
        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setMustChangePassword(MUST_CHANGE);
        adminUserMapper.insert(user);
        log.info("bootstrap administrator created, username={}, password={}, "
                + "this password is printed once and must be changed at first login", username, password);
    }

    private String generatePassword() {
        byte[] raw = new byte[PASSWORD_BYTES];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
