package de.opeldus.datacontract.review;

import java.util.UUID;

/** Anything that goes through the review workflow and ends up in Git. */
public interface ReviewSubject {

    UUID getId();

    ReviewStatus getStatus();

    void setStatus(ReviewStatus status);

    String getCreatedBy();

    void touch();

    void markPublished(String commit);
}
