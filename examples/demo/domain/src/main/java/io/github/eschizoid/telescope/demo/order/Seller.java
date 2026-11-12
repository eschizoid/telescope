package io.github.eschizoid.telescope.demo.order;

/** The shop's own details, as configuration supplies them, printed on every invoice. */
public record Seller(String sellerName, String sellerVatId) {}
