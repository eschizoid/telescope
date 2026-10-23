package io.github.eschizoid.telescope.examples.graphql.model;

/**
 * A ticket's conversion written by hand, which {@link Booking#VIEW}'s via row calls on each ticket.
 */
public final class TicketViews {

  private TicketViews() {}

  public static TicketView forward(final Ticket ticket) {
    return new TicketView(ticket.code(), ticket.seat());
  }

  public static Ticket backward(final TicketView view) {
    return new Ticket(view.reference(), view.seat());
  }
}
