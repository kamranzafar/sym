package org.kamranzafar.sym;

/** An image the service refuses: empty, too large, of a type it doesn't take, or not what its type says. */
public class InvalidImageException extends RuntimeException {

    public enum Reason { EMPTY, TOO_LARGE, UNSUPPORTED_TYPE, NOT_THAT_TYPE }

    private final Reason reason;

    public InvalidImageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
