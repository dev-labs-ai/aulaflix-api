package com.devlabs.aulaflix.exception;

/** One reason the payment provider gave for a refusal: its own code, and its description, in Portuguese. */
public record ProviderError(String code, String description) {
}
