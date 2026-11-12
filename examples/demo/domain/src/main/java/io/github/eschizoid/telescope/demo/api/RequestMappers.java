package io.github.eschizoid.telescope.demo.api;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The mappers from the shapes requests arrive in to {@link PlaceOrder}, the one shape the service
 * places an order from. They live beside their sources, so each generated bridge sits in its
 * source's package, where a mapper looked up by its pair alone finds it.
 */
public final class RequestMappers {

  private RequestMappers() {}

  /**
   * The mobile app's order, mapped to the request the service places orders from. The app names
   * three fields its own way and both fields of each item, so the items convert through {@link
   * ItemsV2}; the app's set of labels becomes the service's list of tags without a row.
   */
  @Bridge
  public static final Mapper<PlaceOrderV2, PlaceOrder> V2 = mapper(
    PlaceOrderV2.class,
    PlaceOrder.class,
    to(PlaceOrderV2::buyerEmail, PlaceOrder::customerEmail),
    to(PlaceOrderV2::coupon, PlaceOrder::discountCode),
    via(PlaceOrderV2::items, PlaceOrder::lines, ItemsV2::forward, ItemsV2::backward)
  );

  /**
   * A partner sends every value as text, and every field has the name the service uses, so the
   * mapper needs no row: the quantity converts to an {@code int}, the delivery to the enum by its
   * constant's name and the gift wrap flag to a {@code boolean}.
   */
  @Bridge
  public static final ForwardMapper<ImportedOrder, PlaceOrder> IMPORT = mapperForward(
    ImportedOrder.class,
    PlaceOrder.class
  );
}
