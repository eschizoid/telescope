package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.spring.TelescopePath;
import io.github.eschizoid.telescope.spring.TelescopeTransform;

@TelescopeTransform(from = Counter.class, to = Integer.class, path = "value")
public interface CounterValue extends TelescopePath<Counter, Integer> {}
