package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

/** A sealed source root one of whose cases renames a property through its own bridge. */
@Bridge(SbNoteDto.class)
public sealed interface SbNote permits SbText, SbMark {}
