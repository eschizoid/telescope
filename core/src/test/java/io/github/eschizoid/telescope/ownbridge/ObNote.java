package io.github.eschizoid.telescope.ownbridge;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** A nested pair whose own bridge upper-cases its text into a bean. */
public record ObNote(String text) {
  @Bridge
  public static final Mapper<ObNote, ObNoteBean> BEAN = mapper(
    ObNote.class,
    ObNoteBean.class,
    to(ObNote::text, ObNoteBean::getText, ObUpperFn.class)
  );
}
