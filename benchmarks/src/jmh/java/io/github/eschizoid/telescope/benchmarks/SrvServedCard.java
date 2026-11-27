package io.github.eschizoid.telescope.benchmarks;

/** A target of the same shape as {@link SrvCard}, filled by a row calling a service. */
public record SrvServedCard(String first, String last, int age, String full) {}
