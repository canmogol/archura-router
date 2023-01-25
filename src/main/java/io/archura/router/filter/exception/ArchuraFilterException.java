package io.archura.router.filter.exception;

public class ArchuraFilterException extends RuntimeException {
    public ArchuraFilterException(String message) {
        super(message);
    }

    public ArchuraFilterException(String message, Throwable cause) {
        super(message, cause);
    }
}
