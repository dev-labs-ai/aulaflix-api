package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.devlabs.aulaflix.domain.entity.EmailTemplate;

/** What each template renders: the purchase confirmation writes the amount as Brazil writes money, whatever it is. */
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

    @Test
    void tellsTheStudentTheRefundEndsTheAccessTheOrderOpened() {
        OutboundEmail email = templates.refundNotice("bia@example.com", "Bia", "K7M2Q9XA", 44730,
                "Frontend com React", false);

        assertThat(email.template()).isEqualTo(EmailTemplate.REFUND_NOTICE);
        assertThat(email.recipient()).isEqualTo("bia@example.com");
        assertThat(email.subject()).isEqualTo("Reembolso do pedido K7M2Q9XA");
        assertThat(email.body()).startsWith("Olá, Bia!\n").contains(
                "reembolsando o pedido K7M2Q9XA, de R$ 447,30, do curso Frontend com React",
                "\nCom o reembolso, o acesso ao curso foi encerrado.\n").doesNotContain("continua");
        assertThat(email.headers()).isEmpty();
    }

    @Test
    void tellsTheStudentARefundedDuplicatePaymentLeavesTheirAccess() {
        OutboundEmail email = templates.refundNotice("bia@example.com", "Bia", "K7M2Q9XA", 44730,
                "Frontend com React", true);

        assertThat(email.body())
                .contains("\nEsse pagamento foi em duplicidade: seu acesso ao curso continua liberado.\n")
                .doesNotContain("encerrado");
    }

    @Test
    void namesTheDuplicatePaymentToTheAdminWithTheCallsThatShowAndRefundIt() {
        OutboundEmail email = templates.duplicatePaymentAlert("ana@aulaflix.com.br", "Ana", "K7M2Q9XA", 149700,
                "Frontend com React");

        assertThat(email.template()).isEqualTo(EmailTemplate.DUPLICATE_PAYMENT_ALERT);
        assertThat(email.recipient()).isEqualTo("ana@aulaflix.com.br");
        assertThat(email.subject()).isEqualTo("Pagamento duplicado: pedido K7M2Q9XA");
        assertThat(email.body()).startsWith("Olá, Ana!\n").contains(
                "O pedido K7M2Q9XA, de R$ 1.497,00, do curso Frontend com React, foi pago",
                "\nGET /v1/admin/orders/K7M2Q9XA\n", "\nPOST /v1/admin/orders/K7M2Q9XA/refund\n");
        assertThat(email.headers()).isEmpty();
    }
}
