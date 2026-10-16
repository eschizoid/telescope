package io.github.eschizoid.telescope.sealedroots;

/** A sealed root whose second case is a sealed interface the first case holds. */
public sealed interface SmAnimal permits SmBird, SmMammal {}
