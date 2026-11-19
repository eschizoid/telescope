package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;

/** The same shape as {@link AlphaForm}, for the runtime mapper. */
public record AlphaEdit(Long id, String name, List<BetaEdit> friends) {}
