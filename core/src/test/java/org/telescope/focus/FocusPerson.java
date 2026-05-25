package org.telescope.focus;

import org.telescope.annotations.Focus;

/** Top-level test record for the {@code @Focus} processor. Generates {@code FocusPersonFocus}. */
@Focus
record FocusPerson(String name, int age, FocusAddress address) {}
