package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

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
 * <p>The rule cannot catch every one-digit change: in the 1st digit, whose weight in the second check digit is 11, and
 * in the 6th, whose two weights sum to 11, a change of one up or down keeps both check digits whenever their
 * remainders sit at 0 or 1, which map to the same digit. 10390865605 and 20390865605 are both CPFs, for one. So the
 * property holds for the other nine digits, and the two blind spots are shown for what they are.
 */
class CpfTest {

    /** Any CPF but the eleven of one repeated digit, which pass the rule and are refused all the same. */
    private static final Generator<String> CPFS = Generator.integers(0, 999_999_999)
            .map(base -> Cpfs.withCheckDigits("%09d".formatted(base)))
            .suchThat(cpf -> cpf.chars().distinct().count() > 1);

    /** The positions, from 0, where the rule catches every one-digit change. */
    private static final List<Integer> CAUGHT_POSITIONS = List.of(1, 2, 3, 4, 6, 7, 8, 9, 10);

    private static final Generator<Change> CHANGES = Generator.from(data -> new Change(
            data.generate(CPFS),
            data.generate(Generator.sampledFrom(CAUGHT_POSITIONS)),
            data.generate(Generator.integers(1, 9))));

    @Test
    void acceptsGeneratedCpfs() {
        PropertyChecker.forAll(CPFS, Cpf::isValid);
    }

    @Test
    void rejectsAnyChangeOfOneDigitTheRuleCatches() {
        PropertyChecker.forAll(CHANGES, change -> !Cpf.isValid(change.applied()));
    }

    @Test
    void acceptsTheTwoCpfsOneStepApartInTheFirstDigit() {
        assertThat(Cpf.isValid("10390865605")).isTrue();
        assertThat(Cpf.isValid("20390865605")).isTrue();
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
            char changed = (char) ('0' + (cpf.charAt(position) - '0' + step) % 10);
            return cpf.substring(0, position) + changed + cpf.substring(position + 1);
        }
    }
}
