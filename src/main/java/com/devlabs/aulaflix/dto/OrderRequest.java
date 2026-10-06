package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.PaymentMethod;

/**
 * The violation messages are the API's field codes; the service checks the CPF, and only when it needs one. The
 * Course's id is read as text, so that an id of any shape answers like an unknown Course.
 */
public record OrderRequest(
        @NotNull(message = "required")
        @Schema(description = "The On sale Course to buy", types = "integer", format = "int64", example = "3")
        String courseId,

        @NotNull(message = "required")
        @Schema(description = "How the Order is paid")
        PaymentMethod method,

        @Schema(description = """
                The Student's CPF, asked for only on their first Pix, to make their Asaas customer; never stored. Its \
                dots, dash and spaces are ignored.""", example = "529.982.247-25")
        String cpf) {

    /** Never shows the CPF, wherever the request ends up printed. */
    @Override
    public String toString() {
        return "OrderRequest[courseId=" + courseId + ", method=" + method + "]";
    }
}
