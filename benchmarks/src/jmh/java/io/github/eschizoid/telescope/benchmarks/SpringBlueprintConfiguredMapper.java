package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;

@TelescopeMapper(transformers = { SpringBlueprintCityPrefixTransformer.class, SpringBlueprintCityTransformer.class })
public interface SpringBlueprintConfiguredMapper
  extends TelescopeProjection<SpringBlueprintSource, SpringBlueprintTarget> {}
