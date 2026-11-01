package de.opeldus.datacontract.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    private final AppUserProperties properties;

    public CurrentUser(AppUserProperties properties) {
        this.properties = properties;
    }

    public String username() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    public Identity identity(String username) {
        return properties.users().stream()
                .filter(user -> user.username().equals(username))
                .findFirst()
                .map(user -> new Identity(
                        username,
                        user.displayName() != null ? user.displayName() : username,
                        user.email() != null ? user.email() : username + "@users.noreply.localhost"))
                .orElseGet(() -> new Identity(username, username, username + "@users.noreply.localhost"));
    }

    public record Identity(String username, String displayName, String email) {}
}
