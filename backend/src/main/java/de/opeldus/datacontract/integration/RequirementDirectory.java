package de.opeldus.datacontract.integration;

import de.opeldus.datacontract.mapping.MappingRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Answers questions about requirements from a local copy, so validation never depends on Jira being reachable. Only a
 * definite answer from Jira is stored; while Jira is down, an unchecked key stays unknown instead of counting as missing.
 */
@Service
public class RequirementDirectory {

    private static final Logger log = LoggerFactory.getLogger(RequirementDirectory.class);
    private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9_]+-[1-9][0-9]*$");
    private static final int MIN_QUERY_LENGTH = 2;

    public enum Check { FOUND, NOT_FOUND, UNKNOWN, INVALID_FORMAT }

    private final List<IssueTracker> trackers;
    private final RequirementIssueRepository issues;
    private final MappingRepository mappings;
    private final TransactionTemplate transaction;

    RequirementDirectory(List<IssueTracker> trackers, RequirementIssueRepository issues, MappingRepository mappings,
            TransactionTemplate transaction) {
        this.trackers = trackers;
        this.issues = issues;
        this.mappings = mappings;
        this.transaction = transaction;
    }

    public boolean isConfigured() {
        return !trackers.isEmpty();
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    public Check check(String key) {
        if (!isConfigured()) {
            return Check.UNKNOWN;
        }
        if (!isValidKey(key)) {
            return Check.INVALID_FORMAT;
        }
        return issues.findById(key).map(issue -> issue.found ? Check.FOUND : Check.NOT_FOUND).orElse(Check.UNKNOWN);
    }

    /**
     * Also asks again for keys Jira did not know, because the issue may have been created since. A failed lookup only
     * logs, so saving stays possible during a Jira outage.
     */
    public void resolveUnknown(Collection<String> keys) {
        if (!isConfigured()) {
            return;
        }
        var unknown = new LinkedHashSet<String>();
        for (var key : keys) {
            if (isValidKey(key) && issues.findById(key).map(issue -> !issue.found).orElse(true)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            lookUp(unknown);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(initialDelayString = "${app.atlassian.refresh-interval}", fixedDelayString = "${app.atlassian.refresh-interval}")
    public void refresh() {
        if (!isConfigured()) {
            return;
        }
        var keys = transaction.execute(status -> {
            var referenced = new LinkedHashSet<String>();
            for (var mapping : mappings.findAll()) {
                for (var rule : mapping.getRules()) {
                    if (isValidKey(rule.getRequirementKey())) {
                        referenced.add(rule.getRequirementKey());
                    }
                }
            }
            return referenced;
        });
        if (!keys.isEmpty()) {
            lookUp(keys);
        }
    }

    public List<IssueTracker.Issue> search(String query) {
        if (!isConfigured() || query == null || query.isBlank() || query.trim().length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        try {
            return trackers.get(0).search(query.trim());
        } catch (RuntimeException exception) {
            log.warn("Jira search failed: {}", exception.getClass().getSimpleName());
            return List.of();
        }
    }

    public Optional<IssueTracker.Issue> find(String key) {
        return issues.findById(key)
                .filter(issue -> issue.found)
                .map(issue -> new IssueTracker.Issue(issue.key, issue.summary, issue.statusName, issue.statusCategory,
                        issue.issueType));
    }

    public Optional<String> url(String key) {
        return isConfigured() && isValidKey(key) ? Optional.of(trackers.get(0).issueUrl(key)) : Optional.empty();
    }

    private void lookUp(Set<String> keys) {
        IssueTracker.Lookup result;
        try {
            result = trackers.get(0).fetch(keys);
        } catch (RuntimeException exception) {
            log.warn("Jira lookup failed, requirements stay unchecked: {}", exception.getClass().getSimpleName());
            return;
        }
        var byKey = new HashMap<String, IssueTracker.Issue>();
        result.issues().forEach(issue -> byKey.put(issue.key(), issue));
        var retriable = new LinkedHashSet<>(result.retriable());
        transaction.executeWithoutResult(status -> {
            var updated = new ArrayList<RequirementIssue>();
            for (var key : keys) {
                if (!retriable.contains(key)) {
                    var issue = issues.findById(key).orElseGet(() -> new RequirementIssue(key));
                    issue.update(byKey.get(key));
                    updated.add(issue);
                }
            }
            issues.saveAll(updated);
        });
    }
}
