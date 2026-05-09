package com.pfplaybackend.api.administration.application.util;

import org.springframework.stereotype.Component;

/**
 * Validates admin self-chosen passwords against §5.6 complexity policy:
 * minimum 10 characters; at least one upper, lower, digit, and symbol from
 * !@#$%^&*. Throws {@link IllegalArgumentException} on violation — callers
 * map to AdministratorManagementException.INVALID_NEW_PASSWORD at the
 * service boundary.
 */
@Component
public class AdminPasswordPolicy {

    private static final int MIN_LENGTH = 10;

    public void requireValid(String pwd) {
        if (pwd == null || pwd.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("password too short");
        }
        if (!pwd.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException("password missing uppercase");
        }
        if (!pwd.matches(".*[a-z].*")) {
            throw new IllegalArgumentException("password missing lowercase");
        }
        if (!pwd.matches(".*[0-9].*")) {
            throw new IllegalArgumentException("password missing digit");
        }
        if (!pwd.matches(".*[!@#$%^&*].*")) {
            throw new IllegalArgumentException("password missing symbol");
        }
    }
}
