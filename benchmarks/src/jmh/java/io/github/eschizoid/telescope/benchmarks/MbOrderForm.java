package io.github.eschizoid.telescope.benchmarks;

import java.util.List;

/** An order as a client sends it, which the generated bridge writes into an {@link MbOrder}. */
public record MbOrderForm(List<MbLineForm> lines) {}
