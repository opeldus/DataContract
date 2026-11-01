package de.opeldus.datacontract.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "requirement_issue")
class RequirementIssue {

    @Id
    @Column(name = "issue_key")
    String key;

    boolean found;
    String summary;
    String statusName;
    String statusCategory;
    String issueType;
    Instant checkedAt;

    protected RequirementIssue() {}

    RequirementIssue(String key) {
        this.key = key;
    }

    void update(IssueTracker.Issue issue) {
        found = issue != null;
        summary = issue == null ? null : limit(issue.summary(), 1000);
        statusName = issue == null ? null : limit(issue.status(), 100);
        statusCategory = issue == null ? null : limit(issue.statusCategory(), 50);
        issueType = issue == null ? null : limit(issue.type(), 100);
        checkedAt = Instant.now();
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
