package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** A jar of a {@link Rack}, pointing back at it. */
@Entity
public class Jar {

  @Id
  @GeneratedValue
  private Long id;

  private String label;

  @ManyToOne
  private Rack rack;

  public Jar() {}

  public Jar(final String label) {
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

  public Rack getRack() {
    return rack;
  }

  public void setRack(final Rack rack) {
    this.rack = rack;
  }
}
