package io.github.eschizoid.telescope.codegen.hiddenbox;

/** A target holding the nested container, declared beside it, the only place that can name it. */
@SuppressWarnings("exports")
public record BoxedDst(HiddenBox.Bag<String> items) {}
