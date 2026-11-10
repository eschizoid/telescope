package io.github.telescopeorganization.telescope.frommap;

import io.github.telescopeorganization.telescope.annotations.FromMap;

@FromMap
public record FmAddress(String city, String zip) {}
