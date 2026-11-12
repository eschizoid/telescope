package io.github.eschizoid.telescope.demo.spring.substrate;

import java.util.Map;
import java.util.Set;

/** A shopper as a value. */
public record ShopperRecord(Long id, String name, Set<String> tags, Map<String, String> preferences) {}
