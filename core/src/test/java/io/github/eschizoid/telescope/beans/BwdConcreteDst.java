package io.github.eschizoid.telescope.beans;

import java.util.List;

/** Target declared as the List interface; the forward rebuild lands in the default ArrayList. */
public record BwdConcreteDst(List<String> tags) {}
