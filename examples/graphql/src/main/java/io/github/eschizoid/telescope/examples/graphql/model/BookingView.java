package io.github.eschizoid.telescope.examples.graphql.model;

import java.util.List;

/** The view of a {@link Booking}, its tickets converted one by one. */
public record BookingView(String id, List<TicketView> tickets) {}
