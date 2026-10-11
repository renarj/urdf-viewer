package com.oberasoftware.urdfviewer.service;

public class InvalidUrdfModelException extends RuntimeException {
    public InvalidUrdfModelException(String message) {
        super(message);
    }

    public InvalidUrdfModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
