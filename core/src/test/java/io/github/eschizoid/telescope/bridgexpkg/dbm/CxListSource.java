package io.github.eschizoid.telescope.bridgexpkg.dbm;

import java.util.List;

/** DB-side parent with a GENERIC List whose element is a cross-package same-simple-name type. */
public record CxListSource(List<CxDoc> docs, String name) {}
