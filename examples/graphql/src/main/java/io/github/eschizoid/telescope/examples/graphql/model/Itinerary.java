package io.github.eschizoid.telescope.examples.graphql.model;

import java.util.List;

/**
 * Tickets in the order they are travelled, mapped to {@link ItineraryView} on the runtime path by a
 * via row naming {@link TicketViews}. No {@code @Bridge} declares the pair.
 */
public record Itinerary(String id, List<Ticket> legs) {}
