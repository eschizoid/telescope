package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Codegen test fixture: bridges to {@link SetterBean} via a no-arg constructor + setters. */
@Bridge(SetterBean.class)
public record SetterRecord(String id, String email) {}
