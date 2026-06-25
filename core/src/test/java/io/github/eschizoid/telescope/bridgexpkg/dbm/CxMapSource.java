package io.github.eschizoid.telescope.bridgexpkg.dbm;

import java.util.Map;

/** DB-side parent with a Map whose VALUE is a cross-package same-simple-name type. */
public record CxMapSource(Map<String, CxDoc> byId, String name) {}
