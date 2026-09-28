package io.github.eschizoid.telescope.spring.quickstart;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.conversion.MapperBuilder;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.time.Instant;

@TelescopeMapper
public interface CustomerSummaryProjection extends TelescopeProjection<Customer, CustomerSummary> {
  @Override
  default void translate(final MapperBuilder<Customer, CustomerSummary> mapping) {
    mapping
      .from(Customer::name)
      .to(CustomerSummary::displayName)
      .add(constant(CustomerSummary::source, "crm"), compute(CustomerSummary::generatedAt, Instant::now));
  }
}
