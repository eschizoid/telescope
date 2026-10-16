package io.github.eschizoid.telescope.sealedroots;

/** A sealed root whose first case holds its second case directly. */
public sealed interface SwNode permits SwPair, SwLeaf {}
