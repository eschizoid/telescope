package io.github.eschizoid.telescope;

/** A target carrying only the routed {@code note}, for mappers whose source has no {@code n}. */
public record SourceRootNoteDto(String note) {}
