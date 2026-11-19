package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;

/** An alpha as a client sends it, for the generated bridge. */
public record AlphaForm(Long id, String name, List<BetaForm> friends) {}
