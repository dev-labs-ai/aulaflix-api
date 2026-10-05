package com.devlabs.aulaflix.service;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import com.devlabs.aulaflix.domain.entity.EmailTemplate;

/**
 * Renders each of the outbox's templates, in pt-BR plain text. Links point at the web, {@code webBase}, never at the
 * API, and carry their secret in the fragment, which the browser keeps out of every request line.
 */
public class EmailTemplates {

    private static final Locale BRAZIL = Locale.of("pt", "BR");

    private final String webBase;

    public EmailTemplates(URI webBase) {
        this.webBase = webBase.toString().replaceAll("/+$", "");
    }

    /** The confirmation link, which is also the welcome email. */
    public OutboundEmail confirmationLink(String recipient, String name, String token) {
        return new OutboundEmail(EmailTemplate.CONFIRMATION_LINK, recipient,
                "Boas-vindas à AulaFlix: confirme seu email", """
                Olá, %s!

                Boas-vindas à AulaFlix. Para confirmar seu email, abra o link abaixo:

                %s/confirmar-email#%s

                O link vale por 72 horas. Se você não criou uma conta na AulaFlix, ignore este email.

                Equipe AulaFlix
                """.formatted(name, webBase, token), Map.of());
    }

    /** The Student's record that the Order was paid, and the way into its Course, which is open from now on. */
    public OutboundEmail purchaseConfirmation(String recipient, String name, String orderCode, int amountCents,
                                              String courseTitle, String courseSlug) {
        return new OutboundEmail(EmailTemplate.PURCHASE_CONFIRMATION, recipient,
                "Compra confirmada: " + courseTitle, """
                Olá, %s!

                Recebemos o pagamento do pedido %s, de %s, e o curso %s já está liberado para você.

                Para começar a assistir, abra:

                %s/aprender/%s

                Guarde este email como comprovante da sua compra.

                Equipe AulaFlix
                """.formatted(name, orderCode, reais(amountCents), courseTitle, webBase, courseSlug), Map.of());
    }

    /**
     * An Admin's alert that the Order was a Duplicate payment: paid while its Student already had the Course, so it
     * opened nothing, and an Admin refunds it by hand. It names the Order, never the Student, whom the Order's read
     * shows.
     */
    public OutboundEmail duplicatePaymentAlert(String recipient, String name, String orderCode, int amountCents,
                                               String courseTitle) {
        return new OutboundEmail(EmailTemplate.DUPLICATE_PAYMENT_ALERT, recipient,
                "Pagamento duplicado: pedido " + orderCode, """
                Olá, %s!

                O pedido %s, de %s, do curso %s, foi pago, mas o aluno já tinha acesso ao curso. O pedido não \
                liberou nada: é um pagamento duplicado, que precisa ser reembolsado.

                Para ver o pedido:

                GET /v1/admin/orders/%s

                Para reembolsá-lo:

                POST /v1/admin/orders/%s/refund

                Equipe AulaFlix
                """.formatted(name, orderCode, reais(amountCents), courseTitle, orderCode, orderCode), Map.of());
    }

    /** As Brazil writes money: {@code R$ 1.497,30}. */
    private static String reais(int cents) {
        return "R$ %s,%02d".formatted(String.format(BRAZIL, "%,d", cents / 100), cents % 100);
    }
}
