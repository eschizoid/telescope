package io.github.eschizoid.telescope.spring.quickstart;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;

@TelescopeMapper
public interface CustomerProjection extends TelescopeProjection<Customer, CustomerDto> {}
