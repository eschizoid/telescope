package io.github.eschizoid.telescope.demo.spring.bughunt.bridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Focus;
import java.math.BigDecimal;

/**
 * Record half of a record↔JPA-bean {@code @Bridge} pair. Carries the API-facing shape (immutable
 * canonical-constructor rebuild) and triggers two pieces of generated code at compile time:
 *
 * <ul>
 *   <li>{@code InvoiceLinePath<R>} — the typed navigator, courtesy of {@code @Focus}.
 *   <li>{@code InvoiceLineBridge.BRIDGE} — the {@code Telescope<InvoiceLine, InvoiceLineEntity>}
 *       iso, courtesy of {@code @Bridge}.
 * </ul>
 *
 * <p>The bijection rule (`BridgeProcessor.sameNames`) requires the source and target expose the
 * same field-name set. Here {@code (sku, qty, unitPrice)} maps to {@link InvoiceLineEntity}'s
 * bean-property names of the same spelling. Types match exactly so no typed-transform row is needed
 * — the bridge slots into the IDENTITY branch on every field.
 */
@Focus
@Bridge(InvoiceLineEntity.class)
public record InvoiceLine(String sku, int qty, BigDecimal unitPrice) {}
