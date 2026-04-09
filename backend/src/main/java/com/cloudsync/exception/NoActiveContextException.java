package com.cloudsync.exception;

public class NoActiveContextException extends RuntimeException {
    public NoActiveContextException() {
        super("Wybierz dysk i folder docelowy w ustawieniach.");
    }

    public NoActiveContextException(String message) {
        super(message);
    }
}
