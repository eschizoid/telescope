package io.github.telescopeorganization.telescope;

/** The target of a source-path row: {@code note} is routed, {@code n} pairs by name. */
public record SourceRootDto(String note, int n) {}
