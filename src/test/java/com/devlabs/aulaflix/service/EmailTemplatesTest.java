package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.devlabs.aulaflix.domain.entity.EmailTemplate;

/** The purchase confirmation writes the amount as Brazil writes money, whatever it is. */
class EmailTemplatesTest {

    private final EmailTemplates templates = new EmailTemplates(URI.create("https://aulaflix.com.br/"));

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "44730   | R$ 447,30",
            "149700  | R$ 1.497,00",
            "5       | R$ 0,05",
            "12345678| R$ 123.456,78"})
    void writesTheAmountPaidInReais(int amountCents, String written) {
        OutboundEmail email = templates.purchaseConfirmation("bia@example.com", "Bia", "K7M2Q9XA", amountCents,
                "Frontend com React", "frontend-com-react");

        assertThat(email.template()).isEqualTo(EmailTemplate.PURCHASE_CONFIRMATION);
        assertThat(email.recipient()).isEqualTo("bia@example.com");
        assertThat(email.subject()).isEqualTo("Compra confirmada: Frontend com React");
        assertThat(email.body()).contains("pedido K7M2Q9XA, de " + written + ", e o curso Frontend com React",
                "\nhttps://aulaflix.com.br/aprender/frontend-com-react\n");
        assertThat(email.headers()).isEmpty();
    }
}
