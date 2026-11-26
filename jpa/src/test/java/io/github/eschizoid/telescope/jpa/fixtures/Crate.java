package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import java.util.ArrayList;
import java.util.List;

/**
 * A crate that owns its bottles, with no adder and no remover: a mapper sets each bottle's
 * back-reference from {@code mappedBy}, and a bottle taken out of the list is deleted as an orphan.
 */
@Entity
public class Crate {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @OneToMany(mappedBy = "crate", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private final List<Bottle> bottles = new ArrayList<>();

  public Crate() {}

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<Bottle> getBottles() {
    return bottles;
  }

  /** Gives the list the elements of {@code given}, keeping the list itself. */
  public void setBottles(final List<Bottle> given) {
    bottles.clear();
    bottles.addAll(given);
  }
}
