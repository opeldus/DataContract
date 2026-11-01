package de.opeldus.datacontract.integration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Short timeouts, because the Jira lookup happens while a user waits for a save. */
final class AtlassianClientFactory {

    private AtlassianClientFactory() {}

    static RestClient create(RestClient.Builder builder, AtlassianProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        var credentials = properties.email() + ":" + properties.apiToken();
        return builder
                .baseUrl(properties.baseUrl().replaceAll("/+$", ""))
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .build();
    }
}
