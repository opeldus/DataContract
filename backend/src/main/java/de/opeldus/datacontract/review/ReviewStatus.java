package de.opeldus.datacontract.review;

public enum ReviewStatus {
    DRAFT,
    IN_REVIEW,
    CHANGES_REQUESTED,
    APPROVED,
    PUBLISHED;

    public boolean isEditable() {
        return this == DRAFT || this == CHANGES_REQUESTED;
    }
}
