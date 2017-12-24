package jschmer.x7remote;

public class AssertionException extends Exception {
    public AssertionException() { super(); }
    public AssertionException(String message) { super(message); }
    public AssertionException(String message, Throwable cause) { super(message, cause); }
    public AssertionException(Throwable cause) { super(cause); }
}
