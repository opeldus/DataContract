package de.opeldus.datacontract.change;

/** What a change means for someone reading the contract, from least to most severe. */
public enum ChangeImpact {
    /** Documentation only; a patch version is enough. */
    DOCUMENTATION,
    /** Additions or different content that existing readers survive; needs a minor version. */
    CONTENT,
    /** Existing readers can fail; needs a major version. */
    BREAKING
}
