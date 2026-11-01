package de.opeldus.datacontract.ddl;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code path} limits reading to a folder of the repository; empty means the whole repository. */
@ConfigurationProperties("app.ddl")
public record DdlProperties(String workdir, String remoteUrl, String branch, String path, String username,
        String token) {}
