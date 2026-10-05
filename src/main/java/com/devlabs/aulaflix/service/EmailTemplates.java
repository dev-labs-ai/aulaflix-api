package com.devlabs.aulaflix.service;

import java.net.URI;
import java.util.Map;

import com.devlabs.aulaflix.domain.entity.EmailTemplate;
import com.devlabs.aulaflix.domain.entity.VerificationCodeKind;

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

    /** A 6-digit code, the same template for a reset and a change, each telling what the code is for. */
    public OutboundEmail verificationCode(String recipient, String name, VerificationCodeKind kind, String code) {
        String purpose = switch (kind) {
            case RESET -> "redefinir";
            case CHANGE -> "trocar";
        };
        return new OutboundEmail(EmailTemplate.VERIFICATION_CODE, recipient,
                "Seu código para %s a senha da AulaFlix".formatted(purpose), """
                Olá, %s!

                Seu código para %s a senha da AulaFlix é:

                %s

                O código vale por 15 minutos. Se você não pediu este código, ignore este email: sua senha continua \
                a mesma.

                Equipe AulaFlix
                """.formatted(name, purpose, code), Map.of());
    }

    /** The notice that the password was reset or changed, pointing at the reset in case it wasn't the Student. */
    public OutboundEmail passwordChanged(String recipient, String name) {
        return new OutboundEmail(EmailTemplate.PASSWORD_CHANGED, recipient, "Sua senha da AulaFlix foi alterada", """
                Olá, %s!

                A senha da sua conta na AulaFlix acaba de ser alterada.

                Se não foi você, redefina sua senha agora em %s/redefinir-senha: isso encerra todas as sessões \
                abertas na sua conta.

                Equipe AulaFlix
                """.formatted(name, webBase), Map.of());
    }
}
