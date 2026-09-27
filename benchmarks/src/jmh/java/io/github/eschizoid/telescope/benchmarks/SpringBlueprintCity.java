package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.spring.TelescopePath;
import io.github.eschizoid.telescope.spring.TelescopeTransform;

@TelescopeTransform(from = SpringBlueprintSource.class, to = String.class, path = "address.city")
public interface SpringBlueprintCity extends TelescopePath<SpringBlueprintSource, String> {}
