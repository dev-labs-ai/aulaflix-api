package com.devlabs.aulaflix.command;

/** Where the admin command talks to the person running it. */
public interface Terminal {

    /** Reads a line without echoing it; an empty string when input has ended. */
    String readPassword(String prompt);

    void println(String line);
}
