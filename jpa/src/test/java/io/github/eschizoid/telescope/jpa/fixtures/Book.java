package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** A book, whose author is a lazy association: a proxy until something reads it. */
@Entity
public class Book {

  @Id
  @GeneratedValue
  private Long id;

  private String title;

  @ManyToOne(fetch = FetchType.LAZY)
  private Author author;

  public Book() {}

  public Book(final String title, final Author author) {
    this.title = title;
    this.author = author;
  }

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(final String title) {
    this.title = title;
  }

  public Author getAuthor() {
    return author;
  }

  public void setAuthor(final Author author) {
    this.author = author;
  }
}
