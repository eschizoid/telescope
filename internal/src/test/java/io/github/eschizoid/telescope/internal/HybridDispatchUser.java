package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Top-level {@code @Focus} fixture for {@link HybridDispatchIntegrationTest}. The {@code
 * FocusProcessor} emits a sibling {@code HybridDispatchUserTelescope} holder for this record
 * (ADR-0006 Phase A). Phase B's {@link MetadataHolderProbe} discovers the holder when {@code
 * Telescope.of(HybridDispatchUser.class).field(HybridDispatchUser::name)} dispatches.
 */
@Focus
public record HybridDispatchUser(String name, int age) {}
