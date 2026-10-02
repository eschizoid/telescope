package io.github.eschizoid.telescope.frommapparity;

import io.github.eschizoid.telescope.annotations.FromMap;

/**
 * A {@code @FromMap} type compiled by the build, so a compilation run by a test reads it from its
 * class file, where the source-retained annotation is gone and only its generated binder remains.
 */
@FromMap
public record Bound(String city) {}
