package io.github.eschizoid.telescope.bridgexpkg.bom;

/**
 * BO-side parent: nested type is a same-simple-name record in a different package than the carrier.
 */
public record CxTarget(CxDoc doc, String name) {}
