package io.github.eschizoid.telescope.spring.quickstart;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;

@TelescopeMapper(transformers = CustomerEmailTransformer.class)
public interface NormalizedCustomerProjection extends TelescopeProjection<Customer, CustomerDto> {}
