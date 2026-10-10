package io.github.eschizoid.telescope;

/**
 * A record that nests itself, so a mapper over it visits a nested pair whose source is the same
 * class.
 */
public record SuperRowOuter(String n, SuperRowOuter inner) {}
