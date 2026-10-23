package com.storyflow.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class ReferenceTypeConverter implements AttributeConverter<ReferenceType, String> {

    @Override
    public String convertToDatabaseColumn(ReferenceType attribute) {
        return attribute == null ? ReferenceType.other.name() : attribute.name();
    }

    @Override
    public ReferenceType convertToEntityAttribute(String dbData) {
        return ReferenceType.from(dbData);
    }
}
