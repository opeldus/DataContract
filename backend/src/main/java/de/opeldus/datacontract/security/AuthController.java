package de.opeldus.datacontract.security;

import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CurrentUser currentUser;

    public AuthController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /** Also forces the deferred CSRF token to be written as a cookie, so the SPA can log in. */
    @GetMapping("/me")
    public Me me(Authentication authentication, CsrfToken csrfToken) {
        csrfToken.getToken();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return new Me(false, null, null, List.of());
        }
        var identity = currentUser.identity(authentication.getName());
        var roles = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .filter(authority -> !authority.startsWith("FACTOR_"))
                .toList();
        return new Me(true, identity.username(), identity.displayName(), roles);
    }

    public record Me(boolean authenticated, String username, String displayName, List<String> roles) {}
}
