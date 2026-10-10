package io.github.eschizoid.telescope;

/** A record whose component {@code isOpen} implements {@link SuperRowOpen}. */
public record SuperRowOpenRecord(int n, String isOpen) implements SuperRowOpen {}
