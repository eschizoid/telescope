package io.github.eschizoid.telescope.demo.starter.partner;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outbound product DTO — the wire-format view returned by the API. {@code @Data} synthesises
 * getters/setters/equals/hashCode (which is also what telescope's {@code SETTERS} write strategy
 * consumes to rebuild instances). {@code @Builder} synthesises a fluent builder.
 * {@code @JsonProperty} annotations rename the fields to snake_case in the JSON output without
 * affecting the Java identifiers telescope reads.
 *
 * <p>This submodule is runtime-only — Lombok itself is on the annotation-processor list, but {@code
 * telescope-lombok}'s codegen processor is not. The runtime mapper reads the Lombok-synthesised
 * getters / setters via the standard bean-property convention.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDto {

  @JsonProperty("product_id")
  private Long id;

  @JsonProperty("stock_keeping_unit")
  private String sku;

  @JsonProperty("display_name")
  private String name;

  @JsonProperty("price_cents")
  private long priceCents;
}
