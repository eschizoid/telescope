package io.github.eschizoid.telescope.jpa.fixtures;

/** One item of a pallet as a PATCH sends it: the identifier of one held, or null. */
public record ItemPatch(Long id, String label) {}
