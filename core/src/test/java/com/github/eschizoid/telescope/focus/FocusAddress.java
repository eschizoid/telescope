package com.github.eschizoid.telescope.focus;

import com.github.eschizoid.telescope.annotations.Focus;

/** Top-level test record for the {@code @Focus} processor. Generates {@code FocusAddressFocus}. */
@Focus
record FocusAddress(String city, String zip) {}
