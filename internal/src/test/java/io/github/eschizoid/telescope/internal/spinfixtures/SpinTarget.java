package io.github.eschizoid.telescope.internal.spinfixtures;

/**
 * A public class whose members name public and non-public types, so each member answers the
 * question of whether a class spun outside this package may call it differently.
 */
public class SpinTarget {

  public SpinTarget() {}

  public String name() {
    return "spin";
  }

  public int[] numbers() {
    return new int[] { 1 };
  }

  public Hidden hidden() {
    return new Hidden();
  }

  public Hidden[] hiddens() {
    return new Hidden[0];
  }

  public void accept(final Hidden hidden) {}

  String secret() {
    return "secret";
  }
}
