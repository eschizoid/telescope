package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;

@TelescopeMapper(from = SpringBlueprintSource.class, to = SpringBlueprintTarget.class)
public interface SpringBlueprintMapper {
  SpringBlueprintTarget map(SpringBlueprintSource source);
}
