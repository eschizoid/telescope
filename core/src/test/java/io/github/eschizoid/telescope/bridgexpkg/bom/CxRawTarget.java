package io.github.eschizoid.telescope.bridgexpkg.bom;

import java.util.List;

/**
 * BO-side parent: a generic List of the same-simple-name BO doc, paired with the DB raw subtype.
 */
public record CxRawTarget(List<CxDoc> docs, String name) {}
