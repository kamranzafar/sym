package org.kamranzafar.sym;

/** A request the service refuses: a bad handle, an empty or too long message, or a bad hashtag. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
