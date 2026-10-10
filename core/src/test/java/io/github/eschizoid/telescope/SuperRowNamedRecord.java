package io.github.eschizoid.telescope;

/** A record whose {@code name} component implements {@link SuperRowNamed}. */
public record SuperRowNamedRecord(int n, String name) implements SuperRowNamed {}
