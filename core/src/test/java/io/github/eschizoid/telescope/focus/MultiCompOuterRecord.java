package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Root {@code @Focus} record of a write path whose hop-2 intermediate is a multi-component record.
 * A {@code new MultiCompOuterRecord(null)} leaves the chain null, so a write into {@code
 * mid.address.cityName} must rebuild the multi-component address from a {@code null} previous
 * record without NPE-ing on its off-path component reads.
 */
@Focus
record MultiCompOuterRecord(MultiCompMidRecord mid) {}
