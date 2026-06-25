package io.github.eschizoid.telescope.bridgexpkg.dbm;

/** DB-side parent with a raw-Collection-subtype field whose element is cross-package. */
public record CxRawSource(CxDocList docs, String name) {}
