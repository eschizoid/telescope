package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.annotations.FromMap;

/** One line of a partner's order, every value as text. */
@FromMap
public record ImportedLine(String sku, String quantity) {}
