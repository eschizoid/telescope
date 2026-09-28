package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.TelescopeMapper;

@TelescopeMapper(transformers = { SpringBlueprintCityPrefixTransformer.class, SpringBlueprintCityTransformer.class })
public interface SpringBlueprintConfiguredMapper
  extends TelescopeProjection<SpringBlueprintSource, SpringBlueprintTarget> {}
