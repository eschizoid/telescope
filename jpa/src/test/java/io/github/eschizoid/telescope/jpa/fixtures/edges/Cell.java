package io.github.eschizoid.telescope.jpa.fixtures.edges;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A cell of a {@link Row}. */
@Entity
@Table(name = "row_cell")
public class Cell {

  @Id
  @GeneratedValue
  private Long id;

  private String text;

  public Cell() {}

  public Cell(final String text) {
    this.text = text;
  }

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getText() {
    return text;
  }

  public void setText(final String text) {
    this.text = text;
  }

  @Override
  public String toString() {
    return "Cell(" + id + "," + text + ")";
  }
}
