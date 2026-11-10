package io.github.telescopeorganization.telescope.spring.blueprint;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;

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
