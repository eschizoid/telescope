package io.github.eschizoid.telescope.jpa.fixtures.edges;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
public class Pin {

  @Id
  @GeneratedValue
  private long id;

  private String text;

  public Pin() {}

  public Pin(final String text) {
    this.text = text;
  }

  public long getId() {
    return id;
  }

  public void setId(final long id) {
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
    return "Pin(" + id + "," + text + ")";
  }
}
