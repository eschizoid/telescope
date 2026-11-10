package io.github.telescopeorganization.telescope;

/** {@link SuperRowOuter}'s target, whose nested {@code inner} is a different class. */
public record SuperRowOuterDto(String n, SuperRowInnerDto inner) {}
