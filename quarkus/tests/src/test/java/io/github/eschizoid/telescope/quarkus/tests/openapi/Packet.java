package io.github.eschizoid.telescope.quarkus.tests.openapi;

import io.github.eschizoid.telescope.quarkus.tests.openapi.a.LineDto;

/**
 * The wire type of a consignment, whose line is a class another class shares its simple name with.
 *
 * @param id the consignment's id
 * @param line its line
 */
public record Packet(String id, LineDto line) {}
