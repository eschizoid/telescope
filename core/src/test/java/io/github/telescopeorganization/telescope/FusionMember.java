package io.github.telescopeorganization.telescope;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Element fixture for {@link FusionCrew}. */
@Focus
record FusionMember(String name, String email, int age) {}
