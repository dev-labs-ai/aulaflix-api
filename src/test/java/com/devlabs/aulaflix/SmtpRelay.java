package com.devlabs.aulaflix;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * The SMTP server as the API reaches it in the tests: a relay, in the tests' JVM, in front of Mailpit, which a test can
 * take down or slow down. Down, it drops each connection at once, as a server that is gone; slow, it holds each one
 * without a word, as a server that never answers, until the relay opens again.
 */
public final class SmtpRelay {

    private final String mailpitHost;
    private final IntSupplier mailpitPort;
    private final ServerSocket server;
    private final Set<Socket> held = new HashSet<>();
    private Mode mode = Mode.OPEN;

    public SmtpRelay(String mailpitHost, IntSupplier mailpitPort) {
        this.mailpitHost = mailpitHost;
        this.mailpitPort = mailpitPort;
        try {
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException failure) {
            throw new IllegalStateException("The SMTP relay could not listen", failure);
        }
        Thread.ofVirtual().name("smtp-relay").start(this::accept);
    }

    public String host() {
        return server.getInetAddress().getHostAddress();
    }

    public int port() {
        return server.getLocalPort();
    }

    /**
     * Relays every new connection to Mailpit, and lets go of those held while slow. A connection is dispatched under
     * the same lock, so none accepted while slow is held after the relay opens.
     */
    public synchronized void open() {
        mode = Mode.OPEN;
        held.forEach(SmtpRelay::closeQuietly);
        held.clear();
    }

    public synchronized void takeDown() {
        mode = Mode.DOWN;
    }

    public synchronized void slowDown() {
        mode = Mode.SLOW;
    }

    private synchronized void dispatch(Socket client) {
        switch (mode) {
            case OPEN -> Thread.ofVirtual().start(() -> relay(client));
            case DOWN -> closeQuietly(client);
            case SLOW -> held.add(client);
        }
    }

    public void stop() {
        open();
        closeQuietly(server);
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                dispatch(server.accept());
            } catch (IOException stopped) {
                return;
            }
        }
    }

    private void relay(Socket client) {
        try (client; Socket mailpit = new Socket(mailpitHost, mailpitPort.getAsInt())) {
            Thread upstream = Thread.ofVirtual().start(() -> pipe(client, mailpit));
            pipe(mailpit, client);
            upstream.join();
        } catch (IOException | InterruptedException ended) {
            // One side closed: the session is over either way.
        }
    }

    /** Copies one way until either side closes, then closes both, so the other direction ends too. */
    private static void pipe(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException closed) {
            // Expected when the other direction closes first.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing is best effort: the socket is gone either way.
        }
    }

    private enum Mode { OPEN, DOWN, SLOW }
}
