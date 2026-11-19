package io.github.eschizoid.telescope.jpa.fixtures.edges;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
public class Note {

  @Id
  @GeneratedValue
  private Long id;

  private String text;

  public Note() {}

  public Note(final String text) {
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
    return "Note(" + id + "," + text + ")";
  }
}
