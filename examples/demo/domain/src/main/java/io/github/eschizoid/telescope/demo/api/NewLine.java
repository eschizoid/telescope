package io.github.eschizoid.telescope.demo.api;

/** One item a client orders. The price comes from the catalog, never from the client. */
public record NewLine(String sku, int quantity) {}
