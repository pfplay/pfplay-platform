package com.pfplaybackend.api.administration.application.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminPasswordPolicyTest {

    private final AdminPasswordPolicy policy = new AdminPasswordPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "Aa1!aaaaaa",       // 10 chars
            "P@ssw0rd1234",     // 12 chars
            "Xx1!Yy2@Zz3#"      // mixed
    })
    void accepts_validPassword(String pwd) {
        assertThatNoException().isThrownBy(() -> policy.requireValid(pwd));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short!",           // < 10 chars
            "alllowercase1!",   // missing upper
            "ALLUPPERCASE1!",   // missing lower
            "NoDigitsHere!",    // missing digit
            "NoSymbolsHere1"    // missing symbol
    })
    void rejects_weakPassword(String pwd) {
        assertThatThrownBy(() -> policy.requireValid(pwd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_nullPassword() {
        assertThatThrownBy(() -> policy.requireValid(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
