package io.github.eschizoid.telescope.beans;

import java.util.TreeSet;

/** Target with a concrete TreeSet field; element passes through (String -> String, Comparable). */
public record SetIdDst(TreeSet<String> tags) {}
