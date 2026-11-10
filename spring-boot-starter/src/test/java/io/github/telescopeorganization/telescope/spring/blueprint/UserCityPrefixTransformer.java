package io.github.telescopeorganization.telescope.spring.blueprint;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;

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
