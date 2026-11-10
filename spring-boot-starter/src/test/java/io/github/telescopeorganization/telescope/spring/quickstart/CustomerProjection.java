package io.github.telescopeorganization.telescope.spring.quickstart;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;

@TelescopeMapper
public interface CustomerProjection extends TelescopeProjection<Customer, CustomerDto> {}
