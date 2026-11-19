package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;

/** A beta as a client sends it, for the generated bridge. */
public record BetaForm(Long id, String name, List<AlphaForm> friends) {}
