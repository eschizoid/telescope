package io.github.eschizoid.telescope.jackson.fromjson;

/** The inner level of {@link Outer}, read by its binder without an annotation of its own. */
public record Inner(String id) {}
