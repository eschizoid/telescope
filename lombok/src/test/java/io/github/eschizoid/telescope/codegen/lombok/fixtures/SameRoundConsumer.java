package io.github.eschizoid.telescope.codegen.lombok.fixtures;

/**
 * Fixture: a same-package, same-compilation-pass consumer of a Lombok-emitted {@code <X>Path}
 * navigator. Pins that the Path is visible to user code in the SAME module's source compilation —
 * the failure mode this guards against is the round-deferred emission that used to land the Path
 * only on {@code processingOver()}, after the compiler had already finished symbol resolution for
 * round-1 sources like this one.
 *
 * <p>If this class compiles, the same-module-main-code constraint is gone. If it doesn't, the
 * timing of {@link io.github.eschizoid.telescope.codegen.lombok.LombokFocusProcessor}'s emission
 * regressed.
 */
public final class SameRoundConsumer {

  private SameRoundConsumer() {}

  /** Shouts the email through the typed DataUserPath navigator. */
  public static DataUser shoutEmail(final DataUser user) {
    return DataUserPath.start()
      .email()
      .update(user, s -> s == null ? null : s.toUpperCase());
  }
}
