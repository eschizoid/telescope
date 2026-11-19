package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;

/** The same shape as {@link BetaForm}, for the runtime mapper. */
public record BetaEdit(Long id, String name, List<AlphaEdit> friends) {}
