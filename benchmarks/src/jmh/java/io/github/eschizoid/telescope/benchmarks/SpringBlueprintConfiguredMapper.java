package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.spring.TelescopeMapper;
import io.github.eschizoid.telescope.spring.TelescopeProjection;

@TelescopeMapper(transformers = { SpringBlueprintCityPrefixTransformer.class, SpringBlueprintCityTransformer.class })
public interface SpringBlueprintConfiguredMapper
  extends TelescopeProjection<SpringBlueprintSource, SpringBlueprintTarget> {}
