package com.pfplaybackend.api.party.domain.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CountryCodeTest {

    @Test
    @DisplayName("of — 대문자 2글자는 그대로 생성된다")
    void acceptsTwoUppercaseLetters() {
        assertThat(CountryCode.of("KR").getValue()).isEqualTo("KR");
        assertThat(CountryCode.of("US").getValue()).isEqualTo("US");
        assertThat(CountryCode.of("JP").getValue()).isEqualTo("JP");
    }

    @Test
    @DisplayName("of — null은 허용되지 않는다 (명시적 금지)")
    void rejectsNull() {
        assertThatThrownBy(() -> CountryCode.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("of — 소문자는 거절된다")
    void rejectsLowercase() {
        assertThatThrownBy(() -> CountryCode.of("kr"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("of — 2글자가 아니면 거절된다")
    void rejectsWrongLength() {
        assertThatThrownBy(() -> CountryCode.of("K"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CountryCode.of("KOR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CountryCode.of(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("of — 숫자/특수문자는 거절된다")
    void rejectsNonLetters() {
        assertThatThrownBy(() -> CountryCode.of("K1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CountryCode.of("K!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("equals/hashCode — 같은 값은 equal")
    void equalityByValue() {
        assertThat(CountryCode.of("KR")).isEqualTo(CountryCode.of("KR"));
        assertThat(CountryCode.of("KR")).isNotEqualTo(CountryCode.of("JP"));
        assertThat(CountryCode.of("KR").hashCode()).isEqualTo(CountryCode.of("KR").hashCode());
    }
}
