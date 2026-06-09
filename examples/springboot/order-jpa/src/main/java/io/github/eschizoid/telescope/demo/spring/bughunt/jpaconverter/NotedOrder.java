package io.github.eschizoid.telescope.demo.spring.bughunt.jpaconverter;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Tiny top-level domain record: an id plus a {@link NotedAddress}. Exists only to provide a
 * record/entity pair that telescope can mapper through end-to-end on persist + load.
 */
@Focus
public record NotedOrder(Long id, NotedAddress address) {}
