package io.github.eschizoid.telescope.spring.web.fixtures;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;

/**
 * The bridge holder beside {@link Account}, under the name the forward mapping looks for, for a
 * pair whose components share no name.
 */
public final class AccountToUnrelatedBridge {

  /** Writes the account's id as {@code zzz} and the length of its name as {@code yyy}. */
  public static final Telescope<Account, Unrelated> BRIDGE = Telescope.bridge(
    new BridgeFn<Account, Unrelated>() {
      @Override
      public Unrelated forward(final Account source) {
        return new Unrelated(source.id(), source.name().length());
      }

      @Override
      public Account backward(final Unrelated target) {
        return new Account(target.zzz(), "", "");
      }
    }
  );

  private AccountToUnrelatedBridge() {}
}
