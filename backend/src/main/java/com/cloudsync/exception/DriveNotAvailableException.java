package com.cloudsync.exception;

public class DriveNotAvailableException extends RuntimeException {

    public DriveNotAvailableException(String message) {
        super(message);
    }

    public DriveNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
