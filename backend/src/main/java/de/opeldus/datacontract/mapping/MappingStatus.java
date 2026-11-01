package de.opeldus.datacontract.mapping;

public enum MappingStatus {
    DRAFT,
    IN_REVIEW,
    CHANGES_REQUESTED,
    APPROVED,
    PUBLISHED;

    public boolean isEditable() {
        return this == DRAFT || this == CHANGES_REQUESTED;
    }
}
