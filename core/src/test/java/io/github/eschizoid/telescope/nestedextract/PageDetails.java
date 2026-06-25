package io.github.eschizoid.telescope.nestedextract;

/** Nested target POJO rebuilt from a nested Map<String, Object> by a composed fromMap converter. */
public record PageDetails(Integer pageSize, String exclusiveStartKey) {}
