package io.github.eschizoid.telescope.codegen.lombok.fixtures;

/**
 * Fixture: calls {@code FinalRoundUserTelescope}, a navigator written in the final round, from a
 * method body. javac attributes method bodies after it has entered the final round's files, so this
 * class compiles; it names the navigator in no single-type import and no signature, where javac
 * would not resolve it.
 */
public final class FinalRoundUserCaller {

  private FinalRoundUserCaller() {}

  /** Upper-cases the name through the generated navigator. */
  public static FinalRoundUser shoutName(final FinalRoundUser user) {
    return FinalRoundUserTelescope.of().name().update(user, String::toUpperCase);
  }
}
