package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Focus;

/** Element fixture for {@link FusionCrew}. */
@Focus
record FusionMember(String name, String email, int age) {}
