package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** A line that records the order it was carried over from: no back-reference. */
@Entity
public class ULine {

  @Id
  @GeneratedValue
  private Long id;

  private String label;

  @ManyToOne
  private UOrder replacedFrom;

  public ULine() {}

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

  public UOrder getReplacedFrom() {
    return replacedFrom;
  }

  public void setReplacedFrom(final UOrder replacedFrom) {
    this.replacedFrom = replacedFrom;
  }
}
