package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The flat target {@link NestedPathBenchmark} reads {@link NpOrder}'s nested leaves into, and the
 * flat source it writes into {@link NpOrder} through a {@code @Bridge} field whose rows write the
 * city and the zip two objects down.
 *
 * @param city the address's city
 * @param zip the address's zip
 * @param qty carried across by name
 */
public record NpOrderDto(String city, int zip, int qty) {
  @Bridge
  public static final Mapper<NpOrderDto, NpOrder> ORDER = mapper(
    NpOrderDto.class,
    NpOrder.class,
    to(
      NpOrderDto::city,
      Telescope.of(NpOrder.class).field(NpOrder::customer).field(NpCustomer::address).field(NpAddress::city)
    ),
    to(
      NpOrderDto::zip,
      Telescope.of(NpOrder.class).field(NpOrder::customer).field(NpCustomer::address).field(NpAddress::zip)
    )
  );
}
