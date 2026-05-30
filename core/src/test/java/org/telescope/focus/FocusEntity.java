package org.telescope.focus;

import org.telescope.annotations.Bridge;
import org.telescope.annotations.Focus;

/**
 * Test fixture exercising the bridge hop on the navigator. {@code @Focus} generates {@code
 * FocusEntityPath}; {@code @Bridge(FocusDto.class)} generates {@code FocusEntityBridge}; the
 * combination makes the navigator's {@code asFocusDto()} method emit a Path-returning hop because
 * {@link FocusDto} is itself {@code @Focus}-annotated.
 */
@Focus
@Bridge(FocusDto.class)
record FocusEntity(String id, String email) {}
