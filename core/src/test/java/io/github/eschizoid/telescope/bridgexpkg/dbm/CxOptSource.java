package io.github.eschizoid.telescope.bridgexpkg.dbm;

import java.util.Optional;

/** DB-side parent with an Optional whose element is a cross-package same-simple-name type. */
public record CxOptSource(Optional<CxDoc> doc, String name) {}
