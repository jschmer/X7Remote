package jschmer.x7remote;

public class SendMessageException extends Exception {
    public SendMessageException() { super(); }
    public SendMessageException(String message) { super(message); }
    public SendMessageException(String message, Throwable cause) { super(message, cause); }
    public SendMessageException(Throwable cause) { super(cause); }
}
