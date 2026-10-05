package com.rlibanez.eplsync.exception;

/** Explicit user-facing validation message; library IllegalArgumentExceptions are never trusted. */
public class UserInputException extends IllegalArgumentException {
    public UserInputException() { super("Los parámetros proporcionados no son válidos"); }
    public UserInputException(String message) { super(message); }
    public UserInputException(String message,Throwable cause) { super(message,cause); }
}
