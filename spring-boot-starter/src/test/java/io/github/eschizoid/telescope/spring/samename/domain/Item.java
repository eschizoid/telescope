package io.github.eschizoid.telescope.spring.samename.domain;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * The entity side of a pair whose two types share the simple name {@code Item}. The target cannot
 * be imported into a file declaring a class of the same simple name, so it is written in full.
 */
@Bridge(io.github.eschizoid.telescope.spring.samename.api.Item.class)
public record Item(String name, String note) {}
