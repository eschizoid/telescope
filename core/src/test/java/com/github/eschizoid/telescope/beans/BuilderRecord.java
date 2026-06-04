package com.github.eschizoid.telescope.beans;

import com.github.eschizoid.telescope.annotations.Bridge;

/** Codegen test fixture: bridges to {@link BuilderBean} via its builder. */
@Bridge(BuilderBean.class)
public record BuilderRecord(String id, String email) {}
