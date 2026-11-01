package de.opeldus.datacontract.integration;

/** A failure whose message is safe and meant to be shown to users. */
class IntegrationException extends RuntimeException {

    IntegrationException(String message) {
        super(message);
    }
}
