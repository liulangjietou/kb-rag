package io.kbrag.api.controller;

import io.kbrag.api.dto.ChangePasswordRequest;
import io.kbrag.api.dto.LoginRequest;
import io.kbrag.api.dto.LoginResponse;
import io.kbrag.api.dto.MeResponse;
import io.kbrag.api.filter.AuthInterceptor;
import io.kbrag.app.auth.AuthService;
import io.kbrag.app.auth.LoginTicket;
import io.kbrag.app.auth.TokenStore;
import io.kbrag.common.api.Result;
import io.kbrag.domain.entity.AdminUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console authentication endpoints.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String FORWARDED_SEPARATOR = ",";

    private final AuthService authService;
    private final TokenStore tokenStore;

    /**
     * Verifies credentials and issues a session token.
     *
     * @param request  login payload
     * @param servlet  servlet request, used to resolve the source address for the audit
     * @return session token and the mandatory password rotation flag
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servlet) {
        LoginTicket ticket = authService.login(request.username(), request.password(), clientIp(servlet));
        return Result.success(new LoginResponse(ticket.token(), ticket.mustChangePassword()));
    }

    /**
     * Rotates the password of the caller and ends every existing session.
     *
     * @param request  rotation payload
     * @param username authenticated user
     * @return empty success envelope
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                       @RequestAttribute(AuthInterceptor.ATTR_USERNAME) String username) {
        authService.changePassword(username, request.oldPassword(), request.newPassword());
        return Result.success(null);
    }

    /**
     * Returns the authenticated account.
     *
     * @param username authenticated user
     * @return account view
     */
    @GetMapping("/me")
    public Result<MeResponse> me(@RequestAttribute(AuthInterceptor.ATTR_USERNAME) String username) {
        AdminUser user = authService.currentUser(username);
        return Result.success(new MeResponse(
                user.getUsername(),
                user.mustChangePassword(),
                user.getLastLoginAt() == null ? null : user.getLastLoginAt().toString()));
    }

    /**
     * Ends the current session.
     *
     * @param token bearer token of the current session
     * @return empty success envelope
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestAttribute(AuthInterceptor.ATTR_TOKEN) String token) {
        tokenStore.revoke(token);
        return Result.success(null);
    }

    /**
     * Resolves the caller address, honouring a single reverse proxy hop.
     *
     * @param servlet servlet request
     * @return source address recorded in the login audit
     */
    private String clientIp(HttpServletRequest servlet) {
        String forwarded = servlet.getHeader(HEADER_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(FORWARDED_SEPARATOR)[0].trim();
        }
        return servlet.getRemoteAddr();
    }
}
