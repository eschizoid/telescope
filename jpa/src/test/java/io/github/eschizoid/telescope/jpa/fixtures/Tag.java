package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.util.Objects;

/**
 * A tag of a basket, equal to another of the same label and hashed by it, so writing a new label
 * into a held tag moves it to another bucket of the set that holds it.
 */
@Entity
public class Tag {

  @Id
  @GeneratedValue
  private Long id;

  private String label;

  public Tag() {}

  public Tag(final String label) {
    this.label = label;
  }

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof Tag tag && Objects.equals(label, tag.label);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(label);
  }
}
