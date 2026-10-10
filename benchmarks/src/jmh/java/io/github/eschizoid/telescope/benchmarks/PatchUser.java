package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/** The base side of {@link PatchBenchmark}: five reference components. */
@Bridge(PatchUserDto.class)
public record PatchUser(String id, String email, String name, String city, String role) {}
