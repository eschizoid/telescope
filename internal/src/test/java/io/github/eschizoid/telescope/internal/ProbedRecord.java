package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Top-level {@code @Focus} fixture for {@link MetadataHolderProbeTest}. The {@code FocusProcessor}
 * emits a sibling {@code ProbedRecordTelescope} metadata holder for this record, which the {@link
 * MetadataHolderProbe} discovers at runtime.
 */
@Focus
public record ProbedRecord(String name, int age) {}
