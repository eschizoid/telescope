package io.github.eschizoid.telescope.jpa.fixtures;

/**
 * One item of a crate, a rack or a pallet as a client sends it: the identifier of one held, or
 * null.
 */
public record ItemForm(Long id, String label) {}
