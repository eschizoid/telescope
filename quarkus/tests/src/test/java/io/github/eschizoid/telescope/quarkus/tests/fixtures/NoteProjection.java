package io.github.eschizoid.telescope.quarkus.tests.fixtures;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;

/** The projection for {@code Note -> Memo}. */
@TelescopeMapper
public interface NoteProjection extends TelescopeProjection<Note, Memo> {}
