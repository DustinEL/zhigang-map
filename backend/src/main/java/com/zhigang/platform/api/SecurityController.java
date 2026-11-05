package com.zhigang.platform.api;

import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.security.LoginAttemptLimiter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/security")
public class SecurityController {
    private final AuthenticationManager authenticationManager;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final boolean loginRequired;

    public SecurityController(AuthenticationManager authenticationManager,
                              LoginAttemptLimiter loginAttemptLimiter,
                              @Value("${zhigang.security.dev-permit-all:true}") boolean devPermitAll) {
        this.authenticationManager = authenticationManager;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.loginRequired = !devPermitAll;
    }

    @GetMapping("/csrf")
    public ApiResponse<CsrfToken> csrf(CsrfToken csrfToken) {
        return ApiResponse.success(csrfToken);
    }

    @PostMapping("/professional/login")
    public ApiResponse<ProfessionalSession> login(@RequestBody LoginCommand command,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        String loginKey = clientAddress(request) + ":" + normalize(command.username());
        loginAttemptLimiter.check(loginKey);
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(command.username(), command.password()));
        } catch (AuthenticationException exception) {
            loginAttemptLimiter.failed(loginKey);
            throw new BusinessException("PROFESSIONAL_LOGIN_FAILED", "专业端账号或密码错误");
        }
        loginAttemptLimiter.succeeded(loginKey);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        return ApiResponse.success(sessionView(authentication));
    }

    @GetMapping("/professional/session")
    public ApiResponse<ProfessionalSession> session(Authentication authentication) {
        return ApiResponse.success(sessionView(authentication));
    }

    @PostMapping("/professional/logout")
    public ApiResponse<ProfessionalSession> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.success(new ProfessionalSession(false, null, loginRequired, List.of(), List.of()));
    }

    private ProfessionalSession sessionView(Authentication authentication) {
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName());
        if (!authenticated) return new ProfessionalSession(false, null, loginRequired, List.of(), List.of());
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        return new ProfessionalSession(true, authentication.getName(), loginRequired,
                List.copyOf(roles), capabilities(roles));
    }

    private List<String> capabilities(Set<String> roles) {
        if (roles.contains("EDITOR")) {
            return List.of("IMPORT_JD", "REVIEW_SKILLS", "REVIEW_RECORDS", "CREATE_PROMOTION_BATCH",
                    "PUBLISH_PROMOTION_BATCH", "MANAGE_ROLE_EVOLUTION");
        }
        if (roles.contains("REVIEWER")) return List.of("REVIEW_PROMOTION_BATCH");
        return List.of();
    }

    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record LoginCommand(String username, String password) {
    }

    public record ProfessionalSession(boolean authenticated, String username, boolean loginRequired,
                                      List<String> roles, List<String> capabilities) {
    }
}
