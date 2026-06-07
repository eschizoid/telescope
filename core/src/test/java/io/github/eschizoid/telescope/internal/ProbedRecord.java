package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Top-level {@code @Focus} fixture for {@link MetadataHolderProbeTest}. The {@code FocusProcessor}
 * emits a sibling {@code ProbedRecordTelescope} metadata holder for this record (ADR-0006 Phase A),
 * which Phase B's {@link MetadataHolderProbe} discovers at runtime.
 */
@Focus
public record ProbedRecord(String name, int age) {}
