package com.pfplaybackend.api.party.domain.value;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class CountryCodeConverter implements AttributeConverter<CountryCode, String> {

    @Override
    public String convertToDatabaseColumn(CountryCode countryCode) {
        return countryCode == null ? null : countryCode.getValue();
    }

    @Override
    public CountryCode convertToEntityAttribute(String dbData) {
        return dbData == null ? null : CountryCode.of(dbData);
    }
}
