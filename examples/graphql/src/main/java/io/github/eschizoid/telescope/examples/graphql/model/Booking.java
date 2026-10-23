package io.github.eschizoid.telescope.examples.graphql.model;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;
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
