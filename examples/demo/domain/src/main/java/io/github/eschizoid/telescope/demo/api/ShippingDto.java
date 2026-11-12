package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.annotations.Focus;

/** Where the parcel is, once it has left the warehouse. */
@Focus
public record ShippingDto(String trackingNumber) {}
