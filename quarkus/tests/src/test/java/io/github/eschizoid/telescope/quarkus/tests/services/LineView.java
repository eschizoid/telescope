package io.github.eschizoid.telescope.quarkus.tests.services;

/** A line as a client sees it. */
public record LineView(String sku, int price, String label) {}
