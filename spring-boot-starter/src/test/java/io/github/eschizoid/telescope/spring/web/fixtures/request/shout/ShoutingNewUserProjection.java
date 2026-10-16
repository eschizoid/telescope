package io.github.eschizoid.telescope.spring.web.fixtures.request.shout;

import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.conversion.MapperBuilder;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.request.CreateUserRequest;
import io.github.eschizoid.telescope.spring.web.fixtures.request.NewUser;

/** A second projection for the same pair, telling itself apart by upper-casing the name. */
@TelescopeMapper
public interface ShoutingNewUserProjection extends TelescopeProjection<CreateUserRequest, NewUser> {
  @Override
  default void translate(final MapperBuilder<CreateUserRequest, NewUser> mapping) {
    mapping.add(to(CreateUserRequest::displayName, NewUser::name, String::toUpperCase, name -> name));
    mapping.from(CreateUserRequest::emailAddress).to(NewUser::email);
  }
}
