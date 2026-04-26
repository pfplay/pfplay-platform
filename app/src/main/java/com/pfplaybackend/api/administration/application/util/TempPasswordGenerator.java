package com.pfplaybackend.api.administration.application.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server-side temp password generator for admin invite + reset flows (§5.6).
 * 12-char output; guarantees at least one upper, lower, digit, and symbol.
 *
 * Decision 5 in PR 6 plan: 12 chars (vs spec example "8자") for ~74-bit entropy.
 * Excludes visually-confusable characters (I, O, l, o, 0, 1) so the temp can be
 * read off Slack into a password field without typos.
 */
@Component
public class TempPasswordGenerator {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";  // I, O removed
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";  // l, o removed
    private static final String DIGIT = "23456789";                   // 0, 1 removed
    private static final String SYMBOL = "!@#$%^&*";
    private static final String ALL = UPPER + LOWER + DIGIT + SYMBOL;
    private static final int LENGTH = 12;

    private final SecureRandom random;

    public TempPasswordGenerator() {
        this(new SecureRandom());
    }

    TempPasswordGenerator(SecureRandom random) {
        this.random = random;
    }

    public String generate() {
        List<Character> chars = new ArrayList<>(LENGTH);
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGIT));
        chars.add(pick(SYMBOL));
        for (int i = 4; i < LENGTH; i++) {
            chars.add(pick(ALL));
        }
        Collections.shuffle(chars, random);
        StringBuilder sb = new StringBuilder(LENGTH);
        chars.forEach(sb::append);
        return sb.toString();
    }

    private Character pick(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }
}
