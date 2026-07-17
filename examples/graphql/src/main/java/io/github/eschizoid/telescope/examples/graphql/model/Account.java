package io.github.eschizoid.telescope.examples.graphql.model;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * A GraphQL-facing credential record that bridges to the mutable {@link AccountEntity} a
 * persistence layer would hold. {@code @Bridge(AccountEntity.class)} emits {@code
 * AccountBridge.BRIDGE}, a {@code Telescope<Account, AccountEntity>} built at compile time from
 * typed method calls — the reflection-free codegen conversion path. Kept flat (no nested record, no
 * enum) so the bridge is a clean control for native-image verification.
 */
@Bridge(AccountEntity.class)
public record Account(String username, String email) {}
