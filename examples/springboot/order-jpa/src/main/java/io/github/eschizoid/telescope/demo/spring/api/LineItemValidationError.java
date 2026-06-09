package io.github.eschizoid.telescope.demo.spring.api;

/**
 * Domain-specific error type produced by {@link ValidatedOrderController}'s line-item validation.
 * Carries the offending SKU + an explanation, mirroring the typical "field-error" object Spring
 * apps return as a 400 payload. Jackson serialises the canonical components by name.
 */
public record LineItemValidationError(String sku, int quantity, String message) {}
