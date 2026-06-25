package io.github.eschizoid.telescope.bridgexpkg.bom;

import java.util.Map;

/** BO-side parent: Map value is the same-simple-name BO doc in a different package. */
public record CxMapTarget(Map<String, CxDoc> byId, String name) {}
