package io.github.eschizoid.telescope.consumer;

import static io.github.eschizoid.telescope.Telescope.edits;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.keep;
import static io.github.eschizoid.telescope.mapping.Mapping.service;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.EditMapper;

/**
 * The README's edit mappers, compiled with the starter alone on the class path: no row names the
 * generated identifiers or the back-reference, which telescope-jpa, a dependency of the starter,
 * accounts for at compile time as it does at run time.
 */
public final class OrderMappers {

  private OrderMappers() {}

  @Bridge
  public static final EditMapper<OrderChanges, Order> EDITS = edits(
    OrderChanges.class,
    Order.class,
    keep(Order::getNumber),
    keep(Order::getCustomer),
    keep(Order::getInternalNotes)
  );

  @Bridge
  public static final EditMapper<LineDto, OrderLine> LINE = edits(
    LineDto.class,
    OrderLine.class,
    compute(OrderLine::getUnitPrice, service(Catalog::price)),
    compute(OrderLine::getCostPrice, service(Catalog::cost)),
    drop(LineDto::unitPrice)
  );
}
