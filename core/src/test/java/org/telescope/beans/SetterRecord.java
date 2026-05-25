package org.telescope.beans;

import org.telescope.annotations.BeanBridge;

/** Codegen test fixture: bridges to {@link SetterBean} via a no-arg constructor + setters. */
@BeanBridge(SetterBean.class)
public record SetterRecord(String id, String email) {}
