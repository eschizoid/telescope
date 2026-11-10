package io.github.telescopeorganization.telescope.focus;

import io.github.telescopeorganization.telescope.annotations.Focus;

/**
 * Single-component hop-1 intermediate between {@link MultiCompOuterRecord} and the multi-component
 * {@link MultiCompLeafRecord}. Single-component so the multi-component off-path read (the shape
 * that previously NPE'd) is isolated to the hop-2 {@link MultiCompLeafRecord} rebuild.
 */
@Focus
record MultiCompMidRecord(MultiCompLeafRecord address) {}
