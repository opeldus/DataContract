package de.opeldus.datacontract.integration;

/** Extension point for where generated documentation pages go. */
public interface DocumentationPublisher {

    /**
     * Creates or updates a page. {@code marker} identifies pages this application generated: an existing page with the
     * same title but without the marker was written by someone else and must not be overwritten.
     */
    PublishedPage upsert(String knownPageId, String title, String body, String marker, String versionMessage);

    record PublishedPage(String id, String url) {}
}
