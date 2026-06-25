package io.github.eschizoid.telescope.bridgexpkg.bom;

import java.util.Optional;

/** BO-side parent: Optional element is the same-simple-name BO doc in a different package. */
public record CxOptTarget(Optional<CxDoc> doc, String name) {}
