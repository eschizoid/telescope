package io.github.eschizoid.telescope.ownbridge;

import java.util.List;

/** Holds {@link ObOb}s, each of which refers back to it. */
public record ObOa(String name, List<ObOb> bs) {}
