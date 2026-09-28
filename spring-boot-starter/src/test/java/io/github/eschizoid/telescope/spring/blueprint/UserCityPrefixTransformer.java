package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;
import io.github.eschizoid.telescope.spring.TelescopeTransformer;

@TelescopeTransformer
public interface UserCityPrefixTransformer extends TelescopeTransformation<User, String> {
  @Override
  default Telescope<User, String> path() {
    return Telescope.of(User.class).field(User::address).field(User.Address::city);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("PREFIX:UNKNOWN", value -> "PREFIX:" + value);
  }
}
