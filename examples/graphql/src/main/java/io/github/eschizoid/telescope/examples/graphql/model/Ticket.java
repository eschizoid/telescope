package io.github.eschizoid.telescope.examples.graphql.model;

/**
 * A booking a client holds, mapped to {@link TicketView} by a {@code @Bridge} mapper field. Neither
 * record carries a reflection registration, so the mapper converts it in a native image only
 * through the bridge generated from the field's rows.
 */
public record Ticket(String code, String seat) {}
