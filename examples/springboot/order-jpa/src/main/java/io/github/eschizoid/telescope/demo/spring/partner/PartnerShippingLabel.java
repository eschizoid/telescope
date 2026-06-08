package io.github.eschizoid.telescope.demo.spring.partner;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partner shipping carrier's wire-format DTO — the kind of thing a third-party SDK exposes. Shows
 * three things in one type:
 *
 * <ol>
 *   <li><b>Lombok-annotated POJO graph.</b> {@code @Data} for getters/setters + {@code
 *       equals}/{@code hashCode}, {@code @Builder} for the fluent builder,
 *       {@code @NoArgsConstructor} so the {@code SETTERS} write strategy works,
 *       {@code @AllArgsConstructor} so {@code @Builder} compiles. The {@code telescope-lombok}
 *       processor emits {@code PartnerShippingLabelPath<R>} + {@code PartnerShippingLabelTelescope}
 *       navigators against the synthesised property surface — same shape as a
 *       {@code @BeanFocus}-driven Path, just discovered through the round-deferred processor pass
 *       that lets Lombok's AST patches install first.
 *   <li><b>Jackson-renamed fields.</b> Java property names use {@code camelCase}; the wire format
 *       uses {@code snake_case} (partner SDK convention). {@code @JsonProperty} bridges the two at
 *       marshal time without touching the Java identifiers — telescope reads {@code customer} (the
 *       Java property), Jackson writes {@code ship_to_customer} to JSON. Different concerns, same
 *       class, no conflict.
 *   <li><b>Coexistence with telescope's deep-mapping factory.</b> The shape mirrors {@code Order}'s
 *       nested record graph — {@link PartnerCustomer}, {@link PartnerAddress}, {@link
 *       PartnerLineItem} — so the same {@code Telescope.mapper(...)} call that handles the
 *       record↔entity tree can handle record↔partner too. Same {@code to(rename)} / {@code
 *       via(elementMapper)} / {@code writeBeans(SETTERS)} primitives, just with Lombok beans on the
 *       target side instead of JPA entities.
 * </ol>
 *
 * <p>No {@code @BeanFocus} here on purpose — Lombok's {@code @Data} alone is the codegen trigger
 * (via the {@code :lombok} processor), proving the module isn't reliant on the telescope-specific
 * annotation pair. The {@code telescope-lombok} processor only emits navigators for
 * <em>top-level</em> classes, so {@code PartnerCustomer}, {@code PartnerAddress}, {@code
 * PartnerLineItem} live in their own sibling files in this package.
 *
 * <p>{@code @JsonInclude(NON_NULL)} keeps the partner's JSON output clean when an optional field
 * like {@code gift_wrap} is absent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PartnerShippingLabel {

  /** Internal order id; partner echoes it back for reconciliation. Mirrors {@code Order.id}. */
  @JsonProperty("id")
  private Long id;

  /** Tracking reference the carrier assigns; mirrors {@code Order.orderNumber}. */
  @JsonProperty("tracking_reference")
  private String trackingReference;

  /** Recipient details; mirrors {@code Order.customer}. */
  @JsonProperty("ship_to_customer")
  private PartnerCustomer customer;

  /** Where to ship; mirrors {@code Order.shippingAddress}. */
  @JsonProperty("ship_to_address")
  private PartnerAddress shippingAddress;

  /** Where to bill; mirrors {@code Order.billingAddress}. */
  @JsonProperty("bill_to_address")
  private PartnerAddress billingAddress;

  /** What's in the box; mirrors {@code Order.lineItems}. */
  @JsonProperty("items")
  private List<PartnerLineItem> items;

  /**
   * Optional gift-wrap address; mirrors {@code Order.giftWrap} ({@code Optional<Address>} on the
   * record side). The {@code Optional} ↔ nullable bridge in {@code DeepMap.autoIso} handles the
   * cross-paradigm conversion (see v0.4.1 CHANGELOG).
   */
  @JsonProperty("gift_wrap")
  private PartnerAddress giftWrap;
}
