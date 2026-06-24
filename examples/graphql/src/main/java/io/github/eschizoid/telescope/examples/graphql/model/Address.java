package io.github.eschizoid.telescope.examples.graphql.model;

import io.github.eschizoid.telescope.annotations.FromMap;

/** Nested input-object record. {@code @FromMap} generates {@code AddressFromMap}. */
@FromMap
public record Address(String city, String zip) {}
