package io.github.eschizoid.telescope.jpa.fixtures.edges;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/** A row of a {@link Group}, which holds cells of its own. */
@Entity
@Table(name = "group_row")
public class Row {

  @Id
  @GeneratedValue
  private Long id;

  private String text;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "line_id")
  @OrderBy("id")
  private List<Cell> subs = new ArrayList<>();

  public Row() {}

  public Row(final String text) {
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

  public List<Cell> getSubs() {
    return subs;
  }

  public void setSubs(final List<Cell> subs) {
    this.subs = subs;
  }

  @Override
  public String toString() {
    return "Row(" + id + "," + text + "," + subs + ")";
  }
}
