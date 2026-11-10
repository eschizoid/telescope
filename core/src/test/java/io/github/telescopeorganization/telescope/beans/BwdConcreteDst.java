package io.github.telescopeorganization.telescope.beans;

import java.util.List;

/** Target declared as the List interface; the forward rebuild lands in the default ArrayList. */
public record BwdConcreteDst(List<String> tags) {}
