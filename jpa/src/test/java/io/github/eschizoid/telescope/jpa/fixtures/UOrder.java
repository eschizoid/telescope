package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;

/** A unidirectional one-to-many: the lines' foreign key is owned by the order's join column. */
@Entity
public class UOrder {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "u_order_id")
  private final List<ULine> lines = new ArrayList<>();

  public UOrder() {}

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

  public List<ULine> getLines() {
    return lines;
  }

  public void setLines(final List<ULine> given) {
    lines.clear();
    lines.addAll(given);
  }
}
