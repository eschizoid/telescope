package io.github.eschizoid.telescope.demo.starter.partner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outbound immutable manifest — a frozen wire-format view of a {@code Product}, used for the
 * read-only {@code GET /products/{id}/manifest} endpoint. Deliberately constructor-only: no
 * setters, no Lombok, no no-arg constructor, no builder.
 *
 * <p>That shape proves the per-class {@code writeBean(ProductManifest.class, CONSTRUCTOR)} override
 * in {@link io.github.eschizoid.telescope.demo.starter.mapping.ProductMappers} — the global {@code
 * writeBeans(SETTERS)} default that works for {@code ProductEntity} and {@code ProductDto}
 * physically cannot apply here. The mapper would fail eagerly at {@code Telescope.mapper(...)}
 * construction time if the override were ignored.
 *
 * <p>{@code @JsonCreator} on the canonical constructor tells Jackson which constructor to use for
 * deserialisation (not strictly necessary for an outbound-only DTO, but kept here for symmetry with
 * the inbound flow and to make the manifest round-trippable).
 */
public final class ProductManifest {

  private final Long id;
  private final String sku;
  private final String name;
  private final long priceCents;

  @JsonCreator
  public ProductManifest(
    @JsonProperty("id") final Long id,
    @JsonProperty("sku") final String sku,
    @JsonProperty("name") final String name,
    @JsonProperty("priceCents") final long priceCents
  ) {
    this.id = id;
    this.sku = sku;
    this.name = name;
    this.priceCents = priceCents;
  }

  public Long getId() {
    return id;
  }

  public String getSku() {
    return sku;
  }

  public String getName() {
    return name;
  }

  public long getPriceCents() {
    return priceCents;
  }
}
