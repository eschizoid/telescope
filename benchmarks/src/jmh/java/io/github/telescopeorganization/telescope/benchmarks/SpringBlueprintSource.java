package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SpringBlueprintTarget.class)
public record SpringBlueprintSource(String name, Address address) {
  public record Address(String city) {}
}
