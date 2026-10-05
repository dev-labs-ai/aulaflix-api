package com.devlabs.aulaflix.service;

import java.net.URI;

/**
 * Where Asaas sends a Student back from a Checkout: the Course's checkout page on the web, {@code webBase}, which shows
 * the Order's state. Coming back proves nothing; the page reads the Order. A Student who cancelled on Asaas's page comes
 * back with {@code cancelado=1}, and the page cancels the Order.
 */
public record CheckoutReturns(URI webBase) {

    /** After paying, or once the Checkout expired. */
    URI after(String courseSlug, String orderCode) {
        return URI.create("%s/cursos/%s/comprar?pedido=%s".formatted(base(), courseSlug, orderCode));
    }

    /** After cancelling on Asaas's page. */
    URI afterCancelling(String courseSlug, String orderCode) {
        return URI.create(after(courseSlug, orderCode) + "&cancelado=1");
    }

    private String base() {
        return webBase.toString().replaceAll("/+$", "");
    }
}
