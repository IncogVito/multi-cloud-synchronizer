package com.cloudsync.exception;

public class HostAgentException extends RuntimeException {

    private final String code;

    public HostAgentException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
