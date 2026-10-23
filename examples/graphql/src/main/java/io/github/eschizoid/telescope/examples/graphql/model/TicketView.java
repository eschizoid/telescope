package io.github.eschizoid.telescope.examples.graphql.model;

/** The client-facing view of a {@link Ticket}, its {@code code} published as {@code reference}. */
public record TicketView(String reference, String seat) {}
