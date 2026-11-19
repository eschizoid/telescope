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

/** A group of rows, each of which holds cells: two levels of collections. */
@Entity
@Table(name = "row_group")
public class Group {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "group_id")
  @OrderBy("id")
  private List<Row> lines = new ArrayList<>();

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

  public List<Row> getLines() {
    return lines;
  }

  public void setLines(final List<Row> lines) {
    this.lines = lines;
  }
}
