package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Codegen test fixture: bridges to {@link SetterBean} via a no-arg constructor + setters. */
@Bridge(SetterBean.class)
public record SetterRecord(String id, String email) {}
