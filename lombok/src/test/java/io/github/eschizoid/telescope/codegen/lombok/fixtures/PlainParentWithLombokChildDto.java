package io.github.eschizoid.telescope.codegen.lombok.fixtures;

/**
 * Fixture: plain record target paired with {@link PlainParentWithLombokChild}. NO Lombok on either
 * the parent or its target; the {@code child} field's type ({@link BridgedDataUserDto}) IS
 * Lombok-annotated, which is what exercises the recursive sub-pair deferral path.
 */
public record PlainParentWithLombokChildDto(String id, BridgedDataUserDto child) {}
