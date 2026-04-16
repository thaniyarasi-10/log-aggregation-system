package com.kovanlabs.logcontroller.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class UserAccountRoleConverter implements AttributeConverter<UserAccountRole, String> {

    @Override
    public String convertToDatabaseColumn(UserAccountRole attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public UserAccountRole convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }

        String normalized = dbData.trim().toUpperCase();
        try {
            return UserAccountRole.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
