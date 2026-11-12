package io.github.eschizoid.telescope.demo.nativeimage;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * A packed parcel and its weight. {@code @Bridge} on the class generates {@code
 * ParcelBridge.BRIDGE}, which converts it to the {@link ParcelView} the tracking page shows.
 */
@Bridge(ParcelView.class)
public record Parcel(String orderNumber, int grams) {}
