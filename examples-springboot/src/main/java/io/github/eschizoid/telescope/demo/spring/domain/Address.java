package io.github.eschizoid.telescope.demo.spring.domain;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * A postal address. Embedded inside an {@link Order} as both shipping and billing addresses, and
 * persisted as a JPA {@code @Embeddable} on the entity side.
 *
 * <p>{@link Focus @Focus} triggers {@code FocusProcessor} to emit {@code AddressPath<R>} + {@code
 * AddressTelescope}. The shared {@code OrderMappers} config handles the cross-paradigm conversion
 * between {@code Address} and {@code AddressEmbeddable} via {@code Telescope.mapper(...)}; the
 * generated path navigator powers compile-time-typed deep updates in the codegen controller.
 *
 * <p><b>Note on {@code @Bridge}.</b> {@code @Bridge(AddressEmbeddable.class)} would be the natural
 * fit for this scalar same-name pair, but {@code BridgeProcessor} currently generates a path-hop
 * constructor that's package-private, which breaks when the source and target live in different
 * packages (here {@code domain} vs {@code persistence}). Filing the bug as a follow-up; for now the
 * runtime mapper covers the conversion just fine.
 */
@Focus
public record Address(String street, String city, String state, String zip) {}
