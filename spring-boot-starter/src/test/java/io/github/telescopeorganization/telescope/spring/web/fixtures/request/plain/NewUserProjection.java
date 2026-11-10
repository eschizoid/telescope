package io.github.telescopeorganization.telescope.spring.web.fixtures.request.plain;

import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.conversion.MapperBuilder;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;
import io.github.telescopeorganization.telescope.spring.web.fixtures.request.CreateUserRequest;
import io.github.telescopeorganization.telescope.spring.web.fixtures.request.NewUser;

/**
 * Renames both components and lower-cases the email, which no direct JSON read into NewUser
 * produces.
 */
@TelescopeMapper
public interface NewUserProjection extends TelescopeProjection<CreateUserRequest, NewUser> {
  @Override
  default void translate(final MapperBuilder<CreateUserRequest, NewUser> mapping) {
    mapping.from(CreateUserRequest::displayName).to(NewUser::name);
    mapping.add(to(CreateUserRequest::emailAddress, NewUser::email, String::toLowerCase, email -> email));
  }
}
