package io.github.eschizoid.telescope.benchmarks;

/** The partial side of {@link PatchBenchmark}. Companion of {@link PatchUser}. */
public record PatchUserDto(String id, String email, String name, String city, String role) {}
