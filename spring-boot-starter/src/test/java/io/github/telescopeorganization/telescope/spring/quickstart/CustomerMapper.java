package io.github.telescopeorganization.telescope.spring.quickstart;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;

@TelescopeMapper(from = Customer.class, to = CustomerDto.class)
public interface CustomerMapper {
  CustomerDto map(Customer customer);
}
