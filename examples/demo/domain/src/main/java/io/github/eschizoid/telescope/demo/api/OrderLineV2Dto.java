package io.github.eschizoid.telescope.demo.api;

/**
 * One line as version 2 shows it: the app calls the SKU {@code product}, reads the quantity as a
 * {@code long} and the price as text.
 */
public record OrderLineV2Dto(String product, String name, long quantity, String price) {}
