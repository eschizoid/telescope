package com.github.eschizoid.telescope.focus;

import com.github.eschizoid.telescope.annotations.Focus;

/** Top-level test record for the {@code @Focus} processor. Generates {@code FocusPersonFocus}. */
@Focus
record FocusPerson(String name, int age, FocusAddress address) {}
