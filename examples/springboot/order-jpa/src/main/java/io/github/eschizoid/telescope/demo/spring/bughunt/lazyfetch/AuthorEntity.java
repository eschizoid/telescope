package io.github.eschizoid.telescope.demo.spring.bughunt.lazyfetch;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Hibernate bean twin of {@link Author}. {@code @BeanFocus} drives the codegen-emitted holder. Used
 * as the target of {@link LazyDocumentEntity#getAuthor()}'s {@code @ManyToOne(fetch=LAZY)} relation
 * — Hibernate will return a runtime {@code HibernateProxy} subclass for unresolved author
 * references, not a plain {@link AuthorEntity}.
 */
@Entity
@Table(name = "bh_author")
@BeanFocus
public class AuthorEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;

  public AuthorEntity() {}

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
}
