package io.github.eschizoid.telescope.demo.nativeimage;

import io.github.eschizoid.telescope.demo.order.Delivery;

/** A parcel the warehouse hands to the carrier for one order. */
public record Shipment(String orderNumber, String recipient, Address address, Delivery delivery) {}
