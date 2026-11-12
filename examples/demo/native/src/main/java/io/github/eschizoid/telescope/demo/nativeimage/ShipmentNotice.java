package io.github.eschizoid.telescope.demo.nativeimage;

import io.github.eschizoid.telescope.demo.order.Delivery;

/** The email that tells a customer their parcel is on its way. */
public record ShipmentNotice(String orderNumber, String recipient, Address address, Delivery delivery) {}
