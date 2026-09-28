package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;

@TelescopeTransformer
public interface CounterValueTransformer extends TelescopeTransformation<Counter, Integer> {
  @Override
  default Telescope<Counter, Integer> path() {
    return Telescope.of(Counter.class).field(Counter::value);
  }

  @Override
  default Transformation<Integer> transform() {
    return new Transformation<>(0, n -> n + 1);
  }
}
