package io.mosip.vivid.av;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpokenCodeTest {
    @Test
    void randomCodesHaveTheRequestedLengthAndAreSpacedForDisplay() {
        SpokenCode c = SpokenCode.random(new SecureRandom(), 4);
        assertThat(c.digits()).matches("[0-9]{4}");
        assertThat(c.display()).matches("[0-9] [0-9] [0-9] [0-9]");
        assertThat(SpokenCode.of("4817").display()).isEqualTo("4 8 1 7");
    }

    @Test
    void digitsAreUniformChiSquare() {
        SecureRandom r = new SecureRandom();
        int[] counts = new int[10];
        int n = 10_000;
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < n; i++) {
            SpokenCode c = SpokenCode.random(r, 4);
            distinct.add(c.digits());
            for (char ch : c.digits().toCharArray()) counts[ch - '0']++;
        }
        double expected = n * 4 / 10.0, chi = 0;
        for (int k : counts) chi += (k - expected) * (k - expected) / expected;
        assertThat(chi).as("chi-square, 9 dof, p=0.001 critical 27.88").isLessThan(27.88);
        // 10,000 draws from 10,000 codes: about 63% distinct, far from a fixed or time-derived sequence.
        assertThat(distinct.size()).isGreaterThan(5_500);
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> SpokenCode.random(new SecureRandom(), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpokenCode.of("12a4")).isInstanceOf(IllegalArgumentException.class);
    }
}
