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
 * A cart whose lines an edit mapper writes, each priced on the way in. Its identifier is generated
 * and its lines point back at it through {@code mappedBy}, so neither needs a row; it has no adder.
 */
@Entity
public class Cart {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private List<CartLine> lines = new ArrayList<>();

  public Cart() {}

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

  public List<CartLine> getLines() {
    return lines;
  }

  public void setLines(final List<CartLine> lines) {
    this.lines = lines;
  }
}
