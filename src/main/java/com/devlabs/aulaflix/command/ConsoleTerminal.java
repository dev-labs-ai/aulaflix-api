package com.devlabs.aulaflix.command;

import java.io.Console;
import java.util.Arrays;

/** The terminal the jar was started from, through {@link System#console()}. */
final class ConsoleTerminal implements Terminal {

    private final Console console;

    ConsoleTerminal(Console console) {
        this.console = console;
    }

    @Override
    public String readPassword(String prompt) {
        char[] typed = console.readPassword("%s", prompt);
        if (typed == null) {
            return "";
        }
        String password = new String(typed);
        Arrays.fill(typed, '\0');
        return password;
    }

    @Override
    public void println(String line) {
        console.printf("%s%n", line);
    }
}
