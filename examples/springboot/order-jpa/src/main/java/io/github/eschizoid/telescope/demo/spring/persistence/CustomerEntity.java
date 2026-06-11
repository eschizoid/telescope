package io.github.eschizoid.telescope.demo.spring.persistence;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The Hibernate-managed twin of {@code domain.Customer}. Identical shape, but a mutable bean with
 * setters (Hibernate uses them on hydration) and a JPA-managed auto-generated id.
 *
 * <p>{@link BeanFocus} triggers the codegen processor to emit {@code CustomerEntityTelescope<R>} +
 * {@code CustomerEntityTelescope}. The codegen mapper composes those with the record-side
 * navigators; the runtime mapper ignores both and resolves via reflective method-reference decode.
 */
@Entity
@Table(name = "customer")
@BeanFocus
public class CustomerEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;
  private String email;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "customer_tags", joinColumns = @JoinColumn(name = "customer_id"))
  @Column(name = "tag")
  private Set<String> tags = new LinkedHashSet<>();

  public CustomerEntity() {}

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

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public Set<String> getTags() {
    return tags;
  }

  public void setTags(final Set<String> tags) {
    this.tags = tags;
  }
}
