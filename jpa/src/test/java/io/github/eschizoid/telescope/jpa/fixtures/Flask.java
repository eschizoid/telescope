package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;

/** A flask of a {@link Barrel}, with a generated identifier and a version of its own. */
@Entity
public class Flask {

  @Id
  @GeneratedValue
  private Long id;

  @Version
  private Long version;

  private String wine;

  @ManyToOne
  private Barrel barrel;

  public Flask() {}

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(final Long version) {
    this.version = version;
  }

  public String getWine() {
    return wine;
  }

  public void setWine(final String wine) {
    this.wine = wine;
  }

  public Barrel getBarrel() {
    return barrel;
  }

  public void setBarrel(final Barrel barrel) {
    this.barrel = barrel;
  }
}
