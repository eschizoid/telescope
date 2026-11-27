package io.github.eschizoid.telescope.jpa.fixtures;

/** A flask as a client sends it: the identifier of a held flask, or null for a new one. */
public record FlaskSent(Long id, Long version, String wine) {}
