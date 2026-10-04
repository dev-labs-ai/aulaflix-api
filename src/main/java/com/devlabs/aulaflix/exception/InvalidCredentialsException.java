package com.devlabs.aulaflix.exception;

/** A wrong password, an email with no Account, or an Account the sign-in does not serve: one answer for all. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("The email or the password is wrong");
    }
}
