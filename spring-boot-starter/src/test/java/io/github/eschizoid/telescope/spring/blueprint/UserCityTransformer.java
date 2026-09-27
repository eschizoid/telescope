package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.TelescopeTransformation;
import io.github.eschizoid.telescope.spring.TelescopeTransformer;
import io.github.eschizoid.telescope.spring.Transformation;
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
