package com.cloudsync.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

@Singleton
@Produces
@Requires(classes = {DriveNotAvailableException.class, ExceptionHandler.class})
public class GlobalExceptionHandler implements ExceptionHandler<RuntimeException, HttpResponse<?>> {

    @Serdeable
    public record ErrorResponse(String error, String message) {}

    @Override
    public HttpResponse<?> handle(HttpRequest request, RuntimeException exception) {
        if (exception instanceof NoActiveContextException) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.CONFLICT)
                    .body(new ErrorResponse("NO_ACTIVE_CONTEXT", exception.getMessage()));
        }
        if (exception instanceof DriveNotAvailableException) {
            return HttpResponse.serverError(new ErrorResponse("DRIVE_NOT_AVAILABLE", exception.getMessage()));
        }
        if (exception instanceof PhotoNotSyncedException) {
            return HttpResponse.badRequest(new ErrorResponse("PHOTO_NOT_SYNCED", exception.getMessage()));
        }
        return HttpResponse.serverError(new ErrorResponse("INTERNAL_ERROR", exception.getMessage()));
    }
}
