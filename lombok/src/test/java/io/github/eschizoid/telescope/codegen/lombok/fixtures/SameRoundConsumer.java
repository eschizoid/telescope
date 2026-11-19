package io.github.eschizoid.telescope.codegen.lombok.fixtures;

/**
 * Fixture: a consumer, in the same package and compilation, of the {@code DataUserTelescope}
 * navigator {@link io.github.eschizoid.telescope.codegen.lombok.LombokFocusProcessor} writes. It
 * names the navigator as the declared type of a field, which javac resolves while the processing
 * rounds run, so this class compiles only when the navigator is written before the final round. A
 * call from a method body alone would not tell: javac attributes bodies after it has entered the
 * final round's files.
 */
public final class SameRoundConsumer {

  private static final DataUserTelescope<DataUser> NAVIGATOR = DataUserTelescope.of();

  private SameRoundConsumer() {}

  /** Shouts the email through the typed DataUserTelescope navigator. */
  public static DataUser shoutEmail(final DataUser user) {
    return NAVIGATOR.email().update(user, s -> s == null ? null : s.toUpperCase());
  }
}
