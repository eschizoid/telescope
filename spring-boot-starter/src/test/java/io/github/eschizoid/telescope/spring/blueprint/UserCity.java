package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.spring.TelescopePath;
import io.github.eschizoid.telescope.spring.TelescopeTransform;

@TelescopeTransform(from = User.class, to = String.class, path = "address.city")
public interface UserCity extends TelescopePath<User, String> {}
