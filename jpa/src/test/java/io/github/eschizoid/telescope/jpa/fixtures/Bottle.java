package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** A bottle of a {@link Crate}, pointing back at it through a property with a setter. */
@Entity
public class Bottle {

  @Id
  @GeneratedValue
  private Long id;

  private String label;

  @ManyToOne
  private Crate crate;

  public Bottle() {}

  public Bottle(final String label) {
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

  public Crate getCrate() {
    return crate;
  }

  public void setCrate(final Crate crate) {
    this.crate = crate;
  }
}
