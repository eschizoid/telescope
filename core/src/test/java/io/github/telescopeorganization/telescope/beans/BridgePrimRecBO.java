package io.github.telescopeorganization.telescope.beans;

/** Boxed-wrapper target for {@link BridgePrimRec} (Boolean / Integer against boolean / int). */
public record BridgePrimRecBO(Boolean locked, Integer count, String name) {}
