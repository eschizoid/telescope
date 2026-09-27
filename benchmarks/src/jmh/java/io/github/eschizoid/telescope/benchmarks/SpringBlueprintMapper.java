package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.spring.TelescopeMapper;

@TelescopeMapper(from = SpringBlueprintSource.class, to = SpringBlueprintTarget.class)
public interface SpringBlueprintMapper {
  SpringBlueprintTarget map(SpringBlueprintSource source);
}
