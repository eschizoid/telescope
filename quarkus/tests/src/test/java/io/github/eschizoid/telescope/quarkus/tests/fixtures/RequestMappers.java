package io.github.eschizoid.telescope.quarkus.tests.fixtures;

import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;

/** The mapping from the wire type of a new order to the type the resource methods work with. */
public final class RequestMappers {

  @Bridge
  public static final ForwardMapper<PlaceOrderRequest, PlaceOrder> PLACE = mapperForward(
    PlaceOrderRequest.class,
    PlaceOrder.class,
    to(PlaceOrderRequest::buyer, PlaceOrder::customer)
  );

  private RequestMappers() {}
}
