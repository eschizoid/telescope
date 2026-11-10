package io.github.telescopeorganization.telescope.spring.quickstart;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;

@TelescopeMapper(transformers = CustomerEmailTransformer.class)
public interface NormalizedCustomerProjection extends TelescopeProjection<Customer, CustomerDto> {}
