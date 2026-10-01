package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Map;

/**
 * A plain declaration paired against a target that can receive an order. The weaker declaration and
 * the capable target are the combination where reading the declaration decides one thing and
 * reading the value decides another.
 */
@Bridge(PlainToCmpTgt.class)
public record PlainToCmpSrc(Map<String, SortedParityA> items) {}
