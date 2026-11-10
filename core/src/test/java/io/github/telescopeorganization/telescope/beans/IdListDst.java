package io.github.telescopeorganization.telescope.beans;

import java.util.LinkedList;

/**
 * Target with a concrete LinkedList field whose element passes through unchanged (String ->
 * String).
 */
public record IdListDst(LinkedList<String> tags) {}
