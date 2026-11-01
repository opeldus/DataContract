package de.opeldus.datacontract.integration;

import java.util.Collection;
import java.util.List;

/** Extension point for the system that holds the requirements. */
public interface IssueTracker {

    List<Issue> search(String query);

    /** Keys that are neither in {@code issues} nor in {@code retriable} do not exist or cannot be read. */
    Lookup fetch(Collection<String> keys);

    /** Creates the link, or updates the existing one with the same {@code globalId}. */
    void upsertLink(String issueKey, RemoteLink link);

    /** Succeeds if the link is already gone. */
    void removeLink(String issueKey, String globalId);

    String issueUrl(String issueKey);

    record Issue(String key, String summary, String status, String statusCategory, String type) {}

    record Lookup(List<Issue> issues, List<String> retriable) {}

    record RemoteLink(String globalId, String url, String title, String summary) {}
}
