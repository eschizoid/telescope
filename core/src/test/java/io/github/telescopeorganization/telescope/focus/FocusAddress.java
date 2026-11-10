package io.github.telescopeorganization.telescope.focus;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Top-level test record for the {@code @Focus} processor. Generates {@code FocusAddressFocus}. */
@Focus
record FocusAddress(String city, String zip) {}
