package io.github.eschizoid.telescope.spring.quickstart;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;

@TelescopeMapper(from = Customer.class, to = CustomerDto.class)
public interface CustomerMapper {
  CustomerDto map(Customer customer);
}
