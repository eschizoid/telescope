package io.github.eschizoid.telescope.demo.nativeimage;

/** The carrier's webhook envelope, generic in what it carries. */
public record Envelope<T>(T payload) {}
