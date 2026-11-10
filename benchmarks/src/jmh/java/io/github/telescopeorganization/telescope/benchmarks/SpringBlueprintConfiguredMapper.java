package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;

@TelescopeMapper(transformers = { SpringBlueprintCityPrefixTransformer.class, SpringBlueprintCityTransformer.class })
public interface SpringBlueprintConfiguredMapper
  extends TelescopeProjection<SpringBlueprintSource, SpringBlueprintTarget> {}
