package io.github.eschizoid.telescope.examples.graphql.model;

import io.github.eschizoid.telescope.annotations.Focus;
import java.util.List;

/**
 * Navigator-only model: nothing registers it for reflection, so a native image can rebuild it only
 * through the setters its generated {@code ShiftTelescope} carries.
 */
@Focus
public record Shift(String label, String code, List<Crewmate> crew) {}
