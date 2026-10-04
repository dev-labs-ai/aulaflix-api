package com.devlabs.aulaflix.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Types the given passwords, in order, at each prompt, and keeps what the command prints. */
final class ScriptedTerminal implements Terminal {

    private final Deque<String> passwords;
    private final List<String> prompts = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();

    ScriptedTerminal(String... passwords) {
        this.passwords = new ArrayDeque<>(List.of(passwords));
    }

    @Override
    public String readPassword(String prompt) {
        prompts.add(prompt);
        if (passwords.isEmpty()) {
            throw new AssertionError("Unexpected prompt: " + prompt);
        }
        return passwords.pop();
    }

    @Override
    public void println(String line) {
        lines.add(line);
    }

    List<String> prompts() {
        return prompts;
    }

    String output() {
        return String.join("\n", lines);
    }
}
