package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.Focus;

/** Top-level test record for the {@code @Focus} processor. Generates {@code FocusAddressFocus}. */
@Focus
record FocusAddress(String city, String zip) {}
