package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** A sack on a {@link Pallet}, or on none. */
@Entity
public class Sack {

  @Id
  @GeneratedValue
  private Long id;

  private String label;

  @ManyToOne
  private Pallet pallet;

  public Sack() {}

  public Sack(final String label) {
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

  public Pallet getPallet() {
    return pallet;
  }

  public void setPallet(final Pallet pallet) {
    this.pallet = pallet;
  }
}
