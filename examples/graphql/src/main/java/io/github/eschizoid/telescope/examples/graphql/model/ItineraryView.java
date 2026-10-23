package io.github.eschizoid.telescope.examples.graphql.model;

import java.util.List;

/** The view of an {@link Itinerary}, its legs converted one by one. */
public record ItineraryView(String id, List<TicketView> legs) {}
