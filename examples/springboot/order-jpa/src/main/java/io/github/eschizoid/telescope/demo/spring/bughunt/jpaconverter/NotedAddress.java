package io.github.eschizoid.telescope.demo.spring.bughunt.jpaconverter;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Domain-side analog of {@code Address}, intentionally minimal: only {@code city} matters for this
 * slice. The matching {@link NotedAddressEmbeddable} runs the {@link UppercaseConverter} on its
 * {@code city} column.
 */
@Focus
public record NotedAddress(String city) {}
