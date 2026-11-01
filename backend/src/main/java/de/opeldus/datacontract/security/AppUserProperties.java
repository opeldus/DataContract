package de.opeldus.datacontract.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
public record AppUserProperties(List<User> users) {

    public AppUserProperties {
        users = users == null ? List.of() : users;
    }

    public record User(String username, String password, String displayName, String email, List<String> roles) {}
}
