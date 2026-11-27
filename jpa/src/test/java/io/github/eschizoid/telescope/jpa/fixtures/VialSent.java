package io.github.eschizoid.telescope.jpa.fixtures;

/** A flask as a client sends it, which no edit mapper of its own converts. */
public record VialSent(Long id, Long version, String wine) {}
