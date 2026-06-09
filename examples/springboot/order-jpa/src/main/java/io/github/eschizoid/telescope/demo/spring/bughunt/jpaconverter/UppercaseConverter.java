package io.github.eschizoid.telescope.demo.spring.bughunt.jpaconverter;

import jakarta.persistence.AttributeConverter;

/**
 * JPA {@code AttributeConverter} that uppercases on write and passes through on read.
 *
 * <p>This is the asymmetric bit that makes the round-trip interesting: Hibernate writes {@code
 * "Brooklyn"} as {@code "BROOKLYN"} into the column, then on load returns whatever the column holds
 * (also {@code "BROOKLYN"}). Telescope, however, reads the bean property — which Hibernate has
 * already populated from {@code convertToEntityAttribute(...)} on hydration. So whatever telescope
 * sees on the bean is whatever Hibernate handed back.
 */
public final class UppercaseConverter implements AttributeConverter<String, String> {

  @Override
  public String convertToDatabaseColumn(final String attribute) {
    return attribute == null ? null : attribute.toUpperCase();
  }

  @Override
  public String convertToEntityAttribute(final String dbData) {
    return dbData;
  }
}
