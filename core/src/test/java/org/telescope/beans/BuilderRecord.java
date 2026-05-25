package org.telescope.beans;

import org.telescope.annotations.BeanBridge;

/** Codegen test fixture: bridges to {@link BuilderBean} via its builder. */
@BeanBridge(BuilderBean.class)
public record BuilderRecord(String id, String email) {}
