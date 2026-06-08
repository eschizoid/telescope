package io.github.eschizoid.telescope.demo.spring.partner;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partner-side counterpart of {@code domain.LineItem}. Unit price stays as {@code BigDecimal} here
 * — the partner SDK uses decimals, not cents (no transform row needed for this side, unlike the
 * {@code LineItem ↔ LineItemEntity} pair where the entity uses long cents).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerLineItem {

  @JsonProperty("id")
  private Long id;

  @JsonProperty("sku")
  private String sku;

  @JsonProperty("quantity")
  private int quantity;

  @JsonProperty("unit_price")
  private BigDecimal unitPrice;
}
