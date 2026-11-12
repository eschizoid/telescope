package io.github.eschizoid.telescope.demo.api;

/**
 * Converts one version 2 item to the line the service works with, and back. Written by hand because
 * both of its fields are renamed, and a mapper names this class in a {@code via} row.
 */
public final class ItemsV2 {

  private ItemsV2() {}

  public static NewLine forward(final NewLineV2 item) {
    return new NewLine(item.product(), item.qty());
  }

  public static NewLineV2 backward(final NewLine line) {
    return new NewLineV2(line.sku(), line.quantity());
  }
}
