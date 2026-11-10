package io.github.telescopeorganization.telescope.focus;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.annotations.Focus;

/**
 * Test fixture exercising the bridge hop on the navigator. {@code @Focus} generates {@code
 * FocusEntityTelescope}; {@code @Bridge(FocusDto.class)} generates {@code FocusEntityBridge}; the
 * combination makes the navigator's {@code asFocusDto()} method emit a Path-returning hop because
 * {@link FocusDto} is itself {@code @Focus}-annotated.
 */
@Focus
@Bridge(FocusDto.class)
record FocusEntity(String id, String email) {}
