package org.telescope.focus;

import org.telescope.annotations.Focus;

/** Top-level test record for the {@code @Focus} processor. Generates {@code FocusAddressFocus}. */
@Focus
record FocusAddress(String city, String zip) {}
