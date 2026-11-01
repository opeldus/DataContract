package de.opeldus.datacontract.artifact;

/** A text file that is committed to the artifact repository; {@code path} is relative to its root. */
public record GeneratedArtifact(String path, String content) {}
