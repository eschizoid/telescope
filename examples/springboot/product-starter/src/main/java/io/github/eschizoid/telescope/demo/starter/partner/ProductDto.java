package io.github.eschizoid.telescope.demo.starter.partner;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outbound product DTO — the wire-format view returned by the API. {@code @Data} synthesises
 * getters/setters/equals/hashCode (which is also what the {@code SETTERS} write strategy uses to
 * rebuild instances). {@code @Builder} synthesises a fluent builder. {@code @JsonProperty}
 * annotations rename the fields to snake_case in the JSON output without affecting the Java
 * identifiers telescope reads.
 *
 * <p>The {@code telescope-lombok} processor emits {@code ProductDtoPath<R>} and {@code
 * ProductDtoTelescope} navigators against the synthesised property surface — same shape as a
 * {@code @BeanFocus}-driven Path, just discovered through the round-deferred processor pass that
 * lets Lombok's AST patches install first.
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
