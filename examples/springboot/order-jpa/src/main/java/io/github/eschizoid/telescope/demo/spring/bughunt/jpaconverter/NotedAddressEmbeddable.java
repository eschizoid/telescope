package io.github.eschizoid.telescope.demo.spring.bughunt.jpaconverter;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;

/**
 * Sibling of {@code AddressEmbeddable} but with a single {@code city} field annotated
 * {@code @Convert(converter = UppercaseConverter.class)}. The converter uppercases on write and is
 * a pass-through on read; together with Hibernate's behaviour, this means the row goes into the DB
 * as {@code BROOKLYN} but the bean field after a load reflects whatever the column held.
 *
 * <p>Telescope's bean-side path reads via {@code getCity()} — i.e., it sees whatever value
 * Hibernate parked in the field via the setter during hydration, which is the post-DB value of the
 * converter chain (still {@code BROOKLYN} because {@code convertToEntityAttribute} is pass-through
 * here).
 */
@Embeddable
@BeanFocus
public class NotedAddressEmbeddable {

  @Convert(converter = UppercaseConverter.class)
  private String city;

  public NotedAddressEmbeddable() {}

  public String getCity() {
    return city;
  }

  public void setCity(final String city) {
    this.city = city;
  }
}
