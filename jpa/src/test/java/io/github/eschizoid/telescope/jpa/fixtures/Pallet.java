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
 * A pallet whose sacks outlive it: the association has no orphan removal, so a sack taken out of
 * the list is unlinked by clearing its back-reference, which leaves its row with a null foreign
 * key. No adder and no remover.
 */
@Entity
public class Pallet {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @OneToMany(mappedBy = "pallet", cascade = { CascadeType.PERSIST, CascadeType.MERGE })
  @OrderBy("id")
  private final List<Sack> sacks = new ArrayList<>();

  public Pallet() {}

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

  public List<Sack> getSacks() {
    return sacks;
  }

  /** Gives the list the elements of {@code given}, keeping the list itself. */
  public void setSacks(final List<Sack> given) {
    sacks.clear();
    sacks.addAll(given);
  }
}
