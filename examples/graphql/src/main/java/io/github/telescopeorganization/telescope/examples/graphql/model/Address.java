package io.github.telescopeorganization.telescope.examples.graphql.model;

import io.github.telescopeorganization.telescope.annotations.Focus;
import io.github.telescopeorganization.telescope.annotations.FromMap;

/**
 * Nested input-object record. {@code @FromMap} generates {@code AddressFromMap}; {@code @Focus}
 * lets the {@code UserTelescope} navigator continue fluently into the address.
 */
@Focus
@FromMap
public record Address(String city, String zip) {}
