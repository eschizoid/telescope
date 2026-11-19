package io.github.eschizoid.telescope.jpa.fixtures.edges;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.util.Objects;

@Entity
public class Label {

  @Id
  @GeneratedValue
  private Long id;

  private String label;

  public Label() {}

  public Label(final String label) {
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

  @Override
  public String toString() {
    return "Label(" + id + "," + label + ")";
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof Label t && Objects.equals(label, t.label);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(label);
  }
}
