package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Codegen test fixture: bridges to {@link BuilderBean} via its builder. */
@Bridge(BuilderBean.class)
public record BuilderRecord(String id, String email) {}
