package com.chronos.tracker.tracking;

/** Inserção manual recusada; a mensagem é mostrada ao usuário como está. */
public final class InvalidManualEntryException extends Exception {
    public InvalidManualEntryException(String message) {
        super(message);
    }
}
