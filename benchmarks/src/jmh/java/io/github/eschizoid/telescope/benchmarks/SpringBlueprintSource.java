package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SpringBlueprintTarget.class)
public record SpringBlueprintSource(String name, Address address) {
  public record Address(String city) {}
}
