package io.github.telescopeorganization.telescope.focus;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Top-level test record for the {@code @Focus} processor. Generates {@code FocusPersonFocus}. */
@Focus
record FocusPerson(String name, int age, FocusAddress address) {}
