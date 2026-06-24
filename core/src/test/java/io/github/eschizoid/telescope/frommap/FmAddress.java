package io.github.eschizoid.telescope.frommap;

import io.github.eschizoid.telescope.annotations.FromMap;

@FromMap
public record FmAddress(String city, String zip) {}
