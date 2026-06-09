package io.github.eschizoid.telescope.demo.spring.bughunt.jpaconverter;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Hibernate-managed twin of {@link NotedOrder}. Embeds {@link NotedAddressEmbeddable}, whose single
 * column carries the {@code @Convert(converter = UppercaseConverter.class)} hint.
 */
@Entity
@Table(name = "noted_orders")
@BeanFocus
public class NotedOrderEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Embedded
  private NotedAddressEmbeddable address;

  public NotedOrderEntity() {}

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public NotedAddressEmbeddable getAddress() {
    return address;
  }

  public void setAddress(final NotedAddressEmbeddable address) {
    this.address = address;
  }
}
