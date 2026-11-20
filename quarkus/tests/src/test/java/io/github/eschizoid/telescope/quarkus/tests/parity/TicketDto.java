package io.github.eschizoid.telescope.quarkus.tests.parity;

/**
 * The target of the pair; {@code via} says which bridge wrote it.
 *
 * @param id the ticket's id
 * @param via the bridge that mapped it
 */
public record TicketDto(String id, String via) {}
