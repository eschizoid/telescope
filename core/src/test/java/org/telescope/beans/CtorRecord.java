package org.telescope.beans;

import org.telescope.annotations.BeanBridge;

/** Codegen test fixture: bridges to {@link CtorPojo} via its all-args constructor. */
@BeanBridge(CtorPojo.class)
public record CtorRecord(String id, String email) {}
