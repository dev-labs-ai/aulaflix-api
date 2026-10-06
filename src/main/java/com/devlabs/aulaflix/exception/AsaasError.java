package com.devlabs.aulaflix.exception;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One of the errors Asaas words a refusal with: its code, such as {@code invalid_value}, and its description, in
 * Portuguese, which may repeat what was sent, so it is shown only where the caller knows what that was.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AsaasError(String code, String description) {
}
