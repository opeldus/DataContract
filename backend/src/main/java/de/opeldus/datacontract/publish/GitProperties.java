package de.opeldus.datacontract.publish;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code remoteUrl} and {@code token} stay server-side; end users never need Git access. */
@ConfigurationProperties("app.git")
public record GitProperties(
        String workdir, String remoteUrl, String branch, String username, String token, String botName,
        String botEmail) {}
