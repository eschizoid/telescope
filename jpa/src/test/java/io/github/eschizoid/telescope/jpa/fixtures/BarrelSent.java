package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/** A barrel as a client sends it, carrying the identifier and the version it last read. */
public record BarrelSent(Long id, int version, String label, List<FlaskSent> flasks) {}
