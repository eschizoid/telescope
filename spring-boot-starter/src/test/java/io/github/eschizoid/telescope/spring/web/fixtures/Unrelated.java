package io.github.eschizoid.telescope.spring.web.fixtures;

/** A response type no component of {@link User} or {@link Account} is named like. */
public record Unrelated(String zzz, int yyy) {}
