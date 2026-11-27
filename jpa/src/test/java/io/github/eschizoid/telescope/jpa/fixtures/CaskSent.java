package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/** A barrel as a client sends it, its flasks converted by name. */
public record CaskSent(Long id, int version, String label, List<VialSent> flasks) {}
