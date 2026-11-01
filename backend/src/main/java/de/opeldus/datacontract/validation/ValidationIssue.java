package de.opeldus.datacontract.validation;

import java.util.UUID;

/** {@code ruleId} and {@code field} are null for issues that concern the mapping as a whole. */
public record ValidationIssue(Severity severity, String code, String message, UUID ruleId, String field) {

    public enum Severity { ERROR, WARNING }

    static ValidationIssue error(String code, String message, UUID ruleId, String field) {
        return new ValidationIssue(Severity.ERROR, code, message, ruleId, field);
    }

    static ValidationIssue warning(String code, String message, UUID ruleId, String field) {
        return new ValidationIssue(Severity.WARNING, code, message, ruleId, field);
    }
}
