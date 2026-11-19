package io.github.eschizoid.telescope.jackson.fromjson;

/** Declares a static nested record, read as any top-level type is. */
public final class Holder {

  private Holder() {}

  /** A nested record. */
  public record Nested(int q, String tag) {}
}
