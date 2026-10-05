package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Base64;
import java.util.Optional;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An unsubscribe token carries the email under AES-GCM, so it opens back into exactly that email, and any change to it,
 * however small, opens into nothing. Emails are generated as any text up to an email's 254 characters, accents and
 * symbols included, since the token must not care what the email holds.
 */
class UnsubscribeTokensTest {

    private static final byte[] KEY = Base64.getDecoder().decode("q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s=");
    private static final byte[] OTHER_KEY = Base64.getDecoder().decode("Zm9vYmFyYmF6cXV4cXV1eGNvcmdlZ3JhdWx0Z2FycGw=");
    private static final String BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final Generator<String> EMAILS = Generator.stringsOf(IntDistribution.uniform(0, 254),
            Generator.anyOf(Generator.asciiPrintableChars(), Generator.charsInRange('À', 'ÿ')));

    private static final Generator<CharacterChange> CHARACTER_CHANGES = Generator.from(data -> {
        String email = data.generate(EMAILS);
        return new CharacterChange(email, data.generate(Generator.naturals()),
                data.generate(Generator.anyOf(Generator.charsFrom(BASE64URL), Generator.asciiPrintableChars())));
    });

    private static final Generator<BitFlip> BIT_FLIPS = Generator.from(data -> new BitFlip(data.generate(EMAILS),
            data.generate(Generator.naturals()), data.generate(Generator.integers(0, 7))));

    private static final Generator<Cut> CUTS = Generator.from(data -> new Cut(data.generate(EMAILS),
            data.generate(Generator.naturals()), data.generate(Generator.booleans())));

    private final UnsubscribeTokens tokens = new UnsubscribeTokens(KEY);

    @Test
    void opensEveryTokenBackIntoItsEmail() {
        PropertyChecker.forAll(EMAILS, email -> tokens.emailIn(tokens.of(email)).equals(Optional.of(email)));
    }

    /**
     * A token never expires, so one sent in an email before any change to how tokens are made must still open. This
     * one was made under {@link #KEY} when tokens were first sent.
     */
    @Test
    void opensATokenSentBefore() {
        assertThat(tokens.emailIn("khgMmMFwPA2mej79tQx-cFdJOP1YjyHxIuHciDB3MBJlKYAkFzSxi3iyvg"))
                .contains("bia@example.com");
    }

    /** The shortest token seals no text at all: only the nonce and the tag. */
    @Test
    void opensATokenOfTheEmptyText() {
        assertThat(tokens.emailIn(tokens.of(""))).contains("");
    }

    /** It goes into a URL's query and fragment as it is, with nothing to escape. */
    @Test
    void writesEveryTokenInBase64UrlWithoutPadding() {
        PropertyChecker.forAll(EMAILS, email -> tokens.of(email).matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void rejectsATokenWithAnyCharacterChanged() {
        PropertyChecker.forAll(CHARACTER_CHANGES, change -> {
            String token = tokens.of(change.email());
            String changed = change.appliedTo(token);
            return changed.equals(token) || tokens.emailIn(changed).isEmpty();
        });
    }

    @Test
    void rejectsATokenWithAnyBitOfItsBytesFlipped() {
        PropertyChecker.forAll(BIT_FLIPS, flip -> tokens.emailIn(flip.appliedTo(tokens.of(flip.email()))).isEmpty());
    }

    @Test
    void rejectsATokenCutShortAtEitherEnd() {
        PropertyChecker.forAll(CUTS, cut -> tokens.emailIn(cut.appliedTo(tokens.of(cut.email()))).isEmpty());
    }

    @Test
    void rejectsATokenMadeUnderAnotherKey() {
        UnsubscribeTokens otherKeys = new UnsubscribeTokens(OTHER_KEY);

        PropertyChecker.forAll(EMAILS, email -> tokens.emailIn(otherKeys.of(email)).isEmpty());
    }

    /** A fresh nonce each time, so two launch emails to one address never show that they share it. */
    @Test
    void makesADifferentTokenEachTimeForTheSameEmail() {
        assertThat(tokens.of("bia@example.com")).isNotEqualTo(tokens.of("bia@example.com"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "not a token", "AAAA", "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s",
            "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s=", "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s+/"})
    void rejectsAnythingItDidNotMake(String token) {
        assertThat(tokens.emailIn(token)).isEmpty();
    }

    /** Base64 padding is never part of a token, so a token with it appended is not the token. */
    @Test
    void rejectsATokenWithPaddingAppended() {
        String token = tokens.of("bia@example.com");

        assertThat(tokens.emailIn(token + "=")).isEmpty();
        assertThat(tokens.emailIn(token + "==")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 16, 24, 31, 33, 64})
    void refusesAKeyOfAnyLengthButAes256s(int length) {
        assertThatIllegalArgumentException().isThrownBy(() -> new UnsubscribeTokens(new byte[length]));
    }

    private record CharacterChange(String email, int position, char replacement) {

        String appliedTo(String token) {
            int at = position % token.length();
            return token.substring(0, at) + replacement + token.substring(at + 1);
        }
    }

    private record BitFlip(String email, int position, int bit) {

        String appliedTo(String token) {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            bytes[position % bytes.length] ^= (byte) (1 << bit);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }

    /** Drops at least one character, at the start or the end, and keeps at least none. */
    private record Cut(String email, int length, boolean fromTheStart) {

        String appliedTo(String token) {
            int dropped = 1 + length % token.length();
            return fromTheStart ? token.substring(dropped) : token.substring(0, token.length() - dropped);
        }
    }
}
