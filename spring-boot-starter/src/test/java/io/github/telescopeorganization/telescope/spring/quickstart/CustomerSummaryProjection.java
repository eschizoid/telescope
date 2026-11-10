package io.github.telescopeorganization.telescope.spring.quickstart;

import static io.github.telescopeorganization.telescope.mapping.Mapping.compute;
import static io.github.telescopeorganization.telescope.mapping.Mapping.constant;
import static io.github.telescopeorganization.telescope.mapping.Mapping.drop;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.conversion.MapperBuilder;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;
import java.time.Instant;

@TelescopeMapper
public interface CustomerSummaryProjection extends TelescopeProjection<Customer, CustomerSummary> {
  @Override
  default void translate(final MapperBuilder<Customer, CustomerSummary> mapping) {
    mapping
      .from(Customer::name)
      .to(CustomerSummary::displayName)
      .add(
        drop(Customer::contact),
        constant(CustomerSummary::source, "crm"),
        compute(CustomerSummary::generatedAt, Instant::now)
      );
  }
}
