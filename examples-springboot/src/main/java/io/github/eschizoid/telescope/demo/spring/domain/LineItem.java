package io.github.eschizoid.telescope.demo.spring.domain;

import io.github.eschizoid.telescope.annotations.Focus;
import java.math.BigDecimal;

/**
 * One line on an order — what was ordered (SKU), how many, and at what unit price. The price is a
 * {@link BigDecimal} on the record side to preserve API-level fidelity, but the persistence
 * equivalent stores cents as a {@code long} (the standard "never trust BigDecimal in the database"
 * pattern). Telescope's typed-transform mapping rows (`Mapping.to(srcAcc, tgtAcc, fwd, bwd)`)
 * bridge the two representations bidirectionally — see {@code RuntimeOrderMappingConfig} and
 * {@code CodegenOrderMappingConfig} for the wire-up.
 */
@Focus
public record LineItem(Long id, String sku, int quantity, BigDecimal unitPrice) {}
