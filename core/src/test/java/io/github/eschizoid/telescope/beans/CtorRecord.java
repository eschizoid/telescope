package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Codegen test fixture: bridges to {@link CtorPojo} via its all-args constructor. */
@Bridge(CtorPojo.class)
public record CtorRecord(String id, String email) {}
