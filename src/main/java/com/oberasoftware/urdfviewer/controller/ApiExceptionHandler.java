package com.oberasoftware.urdfviewer.controller;

import com.oberasoftware.urdfviewer.service.InvalidUrdfModelException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InvalidUrdfModelException.class)
    public ResponseEntity<ApiError> invalidModel(InvalidUrdfModelException error) {
        return ResponseEntity.badRequest().body(new ApiError(Instant.now(), 400, error.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new ApiError(Instant.now(), 413, "The upload exceeds the configured request limit"));
    }

    public record ApiError(Instant timestamp, int status, String message) {
    }
}
