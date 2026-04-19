package com.pfplaybackend.api.party.domain.value;

import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Pattern;

public class CountryCode implements Serializable {

    private static final Pattern PATTERN = Pattern.compile("^[A-Z]{2}$");

    private final String value;

    private CountryCode(String value) {
        this.value = value;
    }

    public static CountryCode of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("CountryCode requires a non-null value; use a null reference for an absent country code");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("CountryCode must be ISO 3166-1 alpha-2 (two uppercase ASCII letters): " + value);
        }
        return new CountryCode(value);
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CountryCode that = (CountryCode) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
