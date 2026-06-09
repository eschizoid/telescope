package io.github.eschizoid.telescope.examples.codegen;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Target of {@link BridgeEntity}'s {@code @Bridge} — same field names (bijection required) and
 * {@code @Focus}-annotated so the entity's bridge hop returns this type's Path.
 */
@Focus
public record BridgeDto(String id, String email) {}
