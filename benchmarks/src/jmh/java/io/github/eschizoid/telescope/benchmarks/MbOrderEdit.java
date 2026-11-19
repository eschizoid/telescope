package io.github.eschizoid.telescope.benchmarks;

import java.util.List;

/** The same shape as {@link MbOrderForm}, which the runtime mapper writes. */
public record MbOrderEdit(List<MbLineEdit> lines) {}
