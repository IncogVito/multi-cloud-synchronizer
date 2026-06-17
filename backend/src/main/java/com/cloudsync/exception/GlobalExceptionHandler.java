package com.cloudsync.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Produces
@Requires(classes = {DriveNotAvailableException.class, ExceptionHandler.class})
public class GlobalExceptionHandler implements ExceptionHandler<RuntimeException, HttpResponse<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
        if (exception instanceof SyncFolderNotConfiguredException) {
            return HttpResponse.badRequest(new ErrorResponse("SYNC_FOLDER_NOT_CONFIGURED", exception.getMessage()));
        }
        LOG.error("Unhandled exception on {} {}: {}", request.getMethod(), request.getPath(), exception.getMessage(), exception);
        return HttpResponse.serverError(new ErrorResponse("INTERNAL_ERROR", exception.getMessage()));
    }
}
