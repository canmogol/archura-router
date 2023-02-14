package io.archura.router.filter.exception;

public class ArchuraFilterException extends RuntimeException {

    private int statusCode = 500;

    public ArchuraFilterException(Throwable cause) {
        super(cause);
    }

    public ArchuraFilterException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ArchuraFilterException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

}
