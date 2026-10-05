package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;

/**
 * The API checks a CPF's check digits itself. CPFs are generated with {@link Cpfs}, which works the check digits out
 * from the Receita Federal's rule on its own, and changed one digit at a time.
 *
 * <p>The rule cannot catch every one-digit change, because remainders 0 and 1 both give the check digit 0. A change
 * keeps both check digits only in two places: the 1st digit, raised or lowered by 1, whose weights are 10 and 11; and
 * the 6th, raised or lowered by 2 or 9, whose weights are 5 and 6. 10390865605 and 20390865605 are both CPFs, and so
 * are 92947822200 and 92947622200. So the property is that a changed CPF stays valid only in those two blind spots.
 */
class CpfTest {

    /** Any CPF but the eleven of one repeated digit, which pass the rule and are refused all the same. */
    private static final Generator<String> CPFS = Generator.integers(0, 999_999_999)
            .map(base -> Cpfs.withCheckDigits("%09d".formatted(base)))
            .suchThat(cpf -> cpf.chars().distinct().count() > 1);

    private static final Generator<Change> CHANGES = Generator.from(data -> new Change(
            data.generate(CPFS),
            data.generate(Generator.integers(0, 10)),
            data.generate(Generator.integers(1, 9))));

    @Test
    void acceptsGeneratedCpfs() {
        PropertyChecker.forAll(CPFS, Cpf::isValid);
    }

    @Test
    void rejectsAnyChangeOfOneDigitOutsideTheRulesBlindSpots() {
        PropertyChecker.forAll(CHANGES, change -> !Cpf.isValid(change.applied()) || change.inABlindSpot());
    }

    @Test
    void acceptsTheTwoCpfsOneStepApartInTheFirstDigit() {
        assertThat(Cpf.isValid("10390865605")).isTrue();
        assertThat(Cpf.isValid("20390865605")).isTrue();
    }

    @Test
    void acceptsTheTwoCpfsTwoStepsApartInTheSixthDigit() {
        assertThat(Cpf.isValid("92947822200")).isTrue();
        assertThat(Cpf.isValid("92947622200")).isTrue();
    }

    @Test
    void acceptsAKnownCpf() {
        assertThat(Cpf.isValid("52998224725")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"11111111111", "00000000000", "99999999999"})
    void rejectsOneDigitRepeated(String cpf) {
        assertThat(Cpf.isValid(cpf)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "5299822472", "529982247250", "5299822472a", "52998224725 "})
    void rejectsAnythingButElevenDigits(String cpf) {
        assertThat(Cpf.isValid(cpf)).isFalse();
    }

    @Test
    void dropsTheDotsTheDashAndTheSpaces() {
        assertThat(Cpf.requireValid(" 529.982.247-25 ")).isEqualTo("52998224725");
    }

    @Test
    void asksForAMissingCpf() {
        assertThatThrownBy(() -> Cpf.requireValid(null)).isInstanceOfSatisfying(InvalidRequestException.class,
                refusal -> assertThat(refusal.violations()).containsExactly(new FieldViolation("cpf", "required")));
    }

    @Test
    void refusesAnInvalidCpf() {
        assertThatThrownBy(() -> Cpf.requireValid("529.982.247-26"))
                .isInstanceOfSatisfying(InvalidRequestException.class, refusal -> assertThat(refusal.violations())
                        .containsExactly(new FieldViolation("cpf", "invalid-cpf")));
    }

    /** The CPF with the digit at the position raised by the step, wrapping past 9. */
    private record Change(String cpf, int position, int step) {

        String applied() {
            return cpf.substring(0, position) + changedDigit() + cpf.substring(position + 1);
        }

        /** The 1st digit moved by 1, or the 6th by 2 or 9: the only changes the check digits can miss. */
        boolean inABlindSpot() {
            int distance = Math.abs(changedDigit() - cpf.charAt(position));
            return position == 0 && distance == 1 || position == 5 && (distance == 2 || distance == 9);
        }

        private char changedDigit() {
            return (char) ('0' + (cpf.charAt(position) - '0' + step) % 10);
        }
    }
}
