package io.github.eschizoid.telescope.codegen.lombok.fixtures;

/**
 * Fixture: plain record target for {@link BridgedGetterSetterUser}'s {@code @Bridge}. Source is
 * Lombok-annotated, target is not — exercises the per-side detection (deferral fires because EITHER
 * side carries Lombok, even if only one).
 */
public record BridgedRecordTarget(String id, String email) {}
