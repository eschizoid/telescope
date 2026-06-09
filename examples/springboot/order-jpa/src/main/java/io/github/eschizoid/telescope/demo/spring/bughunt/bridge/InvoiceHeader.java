package io.github.eschizoid.telescope.demo.spring.bughunt.bridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Focus;
import java.util.List;

/**
 * Deep-recursion case for {@code @Bridge}: a record carrying {@code List<InvoiceLine>}. The parent
 * bridge ({@code InvoiceHeaderBridge}) auto-emits a list-lift that delegates to the user- declared
 * {@link InvoiceLine}↔{@link InvoiceLineEntity} bridge (whose simple name is preserved because that
 * pair is user-declared, not auto-derived).
 */
@Focus
@Bridge(InvoiceHeaderEntity.class)
public record InvoiceHeader(String number, List<InvoiceLine> lines) {}
