package io.github.eschizoid.telescope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ProxyResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The resolution the substrate keys its caches on, over the resolvers registered with core. */
class RegisteredProxyResolversTest {

  /** A value no resolver below knows, and one the second knows as standing for {@code String}. */
  private static final Object PLAIN = new Object();

  private static final Object PROXY = new ArrayList<String>();

  /** Answers nothing, and says so. */
  private static final ProxyResolver INACTIVE = new ProxyResolver() {
    @Override
    public Class<?> classOf(final Object value) {
      throw new AssertionError("an inactive resolver is never asked");
    }

    @Override
    public boolean active() {
      return false;
    }
  };

  /** Knows nothing. */
  private static final ProxyResolver UNKNOWING = value -> null;

  /** Knows {@link #PROXY} as a stand-in for {@code String}. */
  private static final ProxyResolver KNOWING = value -> value == PROXY ? String.class : null;

  /** Would answer {@code Integer} for anything, and is asked only when no earlier one answers. */
  private static final ProxyResolver LATE = value -> Integer.class;

  @Test
  @DisplayName("asks the active resolvers in order, the first answer winning, and passes over an inactive one")
  void asksTheActiveResolversInOrder() {
    final var resolvers = new RegisteredProxyResolvers(List.of(INACTIVE, UNKNOWING, KNOWING, LATE));
    assertTrue(resolvers.active());
    assertEquals(String.class, resolvers.resolve(PROXY));
    assertEquals(Integer.class, resolvers.resolve(PLAIN));
  }

  @Test
  @DisplayName("answers null where no resolver knows the value")
  void answersNullWhereNoResolverKnows() {
    assertNull(new RegisteredProxyResolvers(List.of(UNKNOWING, KNOWING)).resolve(PLAIN));
  }

  @Test
  @DisplayName("is inactive with no resolver, or only inactive ones, so the substrate never asks it")
  void isInactiveWithNoActiveResolver() {
    assertFalse(new RegisteredProxyResolvers(List.of()).active());
    assertFalse(new RegisteredProxyResolvers(List.of(INACTIVE)).active());
    assertFalse(new RegisteredProxyResolvers().active(), "core's own tests register no resolver");
  }
}
