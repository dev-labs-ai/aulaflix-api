package com.devlabs.aulaflix.service;

import java.net.URI;
import java.util.Map;

import com.devlabs.aulaflix.domain.entity.EmailTemplate;

/**
 * Renders each of the outbox's templates, in pt-BR plain text. Links point at the web, {@code webBase}, never at the
 * API, and carry their secret in the fragment, which the browser keeps out of every request line.
 */
public class EmailTemplates {

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
}
