package io.github.telescopeorganization.telescope.spring.blueprint;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

@TelescopeTransformer("userCity")
public interface UserCityTransformer extends TelescopeTransformation<User, String> {
  AtomicInteger BUILDS = new AtomicInteger();
  AtomicInteger TRANSFORM_BUILDS = new AtomicInteger();

  @Override
  default Telescope<User, String> path() {
    BUILDS.incrementAndGet();
    return Telescope.of(User.class).field(User::address).field(User.Address::city);
  }

  @Override
  default Transformation<String> transform() {
    TRANSFORM_BUILDS.incrementAndGet();
    return new Transformation<>(null, value -> value.toLowerCase(Locale.ROOT));
  }
}
