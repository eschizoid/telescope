package io.github.telescopeorganization.telescope.examples.codegen;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.annotations.Focus;

/**
 * A record that is both {@code @Focus}-navigable (gets a {@code BridgeEntityTelescope} navigator)
 * AND bridged to {@link BridgeDto} via {@code @Bridge}. The codegen processor emits both:
 *
 * <ul>
 *   <li>{@code BridgeEntityTelescope<R>} — the per-component navigator
 *   <li>{@code BridgeEntityBridge.BRIDGE} — the {@code Telescope<BridgeEntity, BridgeDto>} iso
 * </ul>
 *
 * <p>Since {@link BridgeDto} is also {@code @Focus}-annotated, the navigator gains an {@code
 * asBridgeDto()} hop returning {@code BridgeDtoTelescope<R>} so navigation continues fluently
 * across the conversion.
 */
@Focus
@Bridge(BridgeDto.class)
public record BridgeEntity(String id, String email) {}
