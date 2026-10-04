package com.materialflow.erp.events;

/** A deterministic input failure that cannot be repaired by retrying the same record. */
public class InvalidEventEnvelopeException extends IllegalArgumentException {
    public InvalidEventEnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
