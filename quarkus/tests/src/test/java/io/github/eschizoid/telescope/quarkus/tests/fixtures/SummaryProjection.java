package io.github.eschizoid.telescope.quarkus.tests.fixtures;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;

/** The projection for {@code Order -> Summary}, which upper-cases the customer first. */
@TelescopeMapper(transformers = ShoutCustomer.class)
public interface SummaryProjection extends TelescopeProjection<Order, Summary> {}
