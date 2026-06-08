package io.github.eschizoid.telescope.demo.starter.persistence;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Hibernate-managed twin of {@link io.github.eschizoid.telescope.demo.starter.domain.Product}.
 * Plain bean (not Lombok — JPA + Lombok {@code @Data}'s id-based {@code equals/hashCode} can break
 * transient/managed comparisons, so we keep the entity layer pure-Java).
 */
@Entity
@Table(name = "product")
@BeanFocus
public class ProductEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String sku;

  private String name;

  private long priceCents;

  public ProductEntity() {}

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getSku() {
    return sku;
  }

  public void setSku(final String sku) {
    this.sku = sku;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public long getPriceCents() {
    return priceCents;
  }

  public void setPriceCents(final long priceCents) {
    this.priceCents = priceCents;
  }
}
