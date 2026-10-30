package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The source {@link NestedPathBenchmark} reads through a path into {@link NpOrderDto}, through the
 * runtime mapper this field holds, whose rows read the city and the zip two objects down.
 *
 * @param customer read through to its address
 * @param qty carried across by name
 */
public record NpOrder(NpCustomer customer, int qty) {
  public static final Mapper<NpOrder, NpOrderDto> DTO = mapper(
    NpOrder.class,
    NpOrderDto.class,
    to(
      Telescope.of(NpOrder.class).field(NpOrder::customer).field(NpCustomer::address).field(NpAddress::city),
      NpOrderDto::city
    ),
    to(
      Telescope.of(NpOrder.class).field(NpOrder::customer).field(NpCustomer::address).field(NpAddress::zip),
      NpOrderDto::zip
    )
  );
}
