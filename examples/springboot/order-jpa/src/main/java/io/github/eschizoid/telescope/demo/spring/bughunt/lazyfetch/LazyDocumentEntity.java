package io.github.eschizoid.telescope.demo.spring.bughunt.lazyfetch;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Hibernate bean twin of {@link Document}. The {@code @ManyToOne(fetch=LAZY)} on {@link #author} is
 * the load-bearing bit — Hibernate hands {@code getAuthor()} back a {@code HibernateProxy} subclass
 * of {@link AuthorEntity} until the proxy is dereferenced. Telescope's bean reflection then keys on
 * {@code proxy.getClass()} (the synthetic subclass) instead of {@link AuthorEntity} for both the
 * {@link io.github.eschizoid.telescope.internal.MetadataHolderProbe MetadataHolderProbe} and the
 * {@link io.github.eschizoid.telescope.internal.Beans Beans} getter / setter caches.
 */
@Entity
@Table(name = "bh_document")
@BeanFocus
public class LazyDocumentEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String title;

  @ManyToOne(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
  @JoinColumn(name = "author_id")
  private AuthorEntity author;

  public LazyDocumentEntity() {}

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

  public AuthorEntity getAuthor() {
    return author;
  }

  public void setAuthor(final AuthorEntity author) {
    this.author = author;
  }
}
