package de.opeldus.datacontract.review;

import java.util.UUID;

/** Anything that goes through the review workflow and ends up in Git. */
public interface ReviewSubject {

    UUID getId();

    /** Stays the same for all copies of this item across releases. */
    UUID getKey();

    /** Null on the mainline. */
    UUID getReleaseId();

    boolean isArchived();

    String getPublishedVersion();

    /** The last state of the base that was taken over; null outside of a release. */
    String getUpstreamVersion();

    /** Records that {@code version} of the base was taken over. */
    void rebaseOn(String version, String commit);

    ReviewStatus getStatus();

    void setStatus(ReviewStatus status);

    String getCreatedBy();

    void touch();

    void markPublished(String commit);
}
