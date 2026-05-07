package com.pfplaybackend.api.administration.application.util;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TempPasswordGeneratorTest {

    private final TempPasswordGenerator generator = new TempPasswordGenerator(new SecureRandom());

    @Test
    void generate_is12CharsLong() {
        assertThat(generator.generate()).hasSize(12);
    }

    @Test
    void generate_containsAtLeastOneOfEachClass() {
        for (int i = 0; i < 100; i++) {
            String pwd = generator.generate();
            assertThat(pwd).matches(".*[A-Z].*");
            assertThat(pwd).matches(".*[a-z].*");
            assertThat(pwd).matches(".*[0-9].*");
            assertThat(pwd).matches(".*[!@#$%^&*].*");
        }
    }

    @Test
    void generate_isReasonablyUnique() {
        Set<String> samples = new HashSet<>();
        for (int i = 0; i < 1000; i++) samples.add(generator.generate());
        assertThat(samples).hasSizeGreaterThan(995); // collisions practically impossible
    }
}
