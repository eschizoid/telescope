package io.github.telescopeorganization.telescope.examples.graphql.model;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.via;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;
import java.util.List;

/**
 * Tickets held together, mapped to {@link BookingView} by a {@code @Bridge} field whose via row
 * converts each ticket through two methods written by hand. The generated bridge calls them, so
 * inside a native image the row's method references are never read.
 */
public record Booking(String id, List<Ticket> tickets) {
  @Bridge
  public static final Mapper<Booking, BookingView> VIEW = mapper(
    Booking.class,
    BookingView.class,
    via(Booking::tickets, BookingView::tickets, TicketViews::forward, TicketViews::backward)
  );
}
