package io.github.eschizoid.telescope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ProxyResolver;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
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

  /** Throws for every value, as a resolver that cannot tell might. */
  private static final ProxyResolver THROWING = value -> {
    throw new IllegalStateException("cannot tell");
  };

  /** Fails to say whether it is active. */
  private static final ProxyResolver UNDECIDED = new ProxyResolver() {
    @Override
    public Class<?> classOf(final Object value) {
      return Long.class;
    }

    @Override
    public boolean active() {
      throw new IllegalStateException("cannot decide");
    }
  };

  /**
   * Registrations as a {@code ServiceLoader} yields them, where each {@code null} stands for one
   * whose constructor threw: {@code next} throws for it and moves past it.
   */
  private static Iterator<ProxyResolver> registrations(final ProxyResolver... entries) {
    final var queue = new ArrayList<ProxyResolver>();
    for (final var entry : entries) {
      queue.add(entry);
    }
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return !queue.isEmpty();
      }

      @Override
      public ProxyResolver next() {
        final var entry = queue.removeFirst();
        if (entry == null) {
          throw new ServiceConfigurationError("BrokenResolver could not be instantiated");
        }
        return entry;
      }
    };
  }

  private static RegisteredProxyResolvers loaded(final List<String> refused, final ProxyResolver... entries) {
    return new RegisteredProxyResolvers(registrations(entries), refused::add);
  }

  @Test
  @DisplayName("asks the active resolvers in order, the first answer winning, and passes over an inactive one")
  void asksTheActiveResolversInOrder() {
    final var refused = new ArrayList<String>();
    final var resolvers = loaded(refused, INACTIVE, UNKNOWING, KNOWING, LATE);
    assertTrue(resolvers.active());
    assertEquals(String.class, resolvers.resolve(PROXY));
    assertEquals(Integer.class, resolvers.resolve(PLAIN));
    assertEquals(List.of(), refused);
  }

  @Test
  @DisplayName("answers null where no resolver knows the value")
  void answersNullWhereNoResolverKnows() {
    assertNull(loaded(new ArrayList<>(), UNKNOWING, KNOWING).resolve(PLAIN));
  }

  @Test
  @DisplayName("loads every other resolver past one that cannot be loaded, and reports that one by name")
  void loadsTheOthersPastOneThatCannotBeLoaded() {
    final var refused = new ArrayList<String>();
    final var resolvers = loaded(refused, null, KNOWING);
    assertEquals(String.class, resolvers.resolve(PROXY));
    assertEquals(1, refused.size());
    assertTrue(refused.getFirst().contains("BrokenResolver"), refused::toString);
  }

  @Test
  @DisplayName("leaves out a resolver that fails to say whether it is active, reporting it, and keeps the others")
  void leavesOutAResolverThatCannotSayWhetherItIsActive() {
    final var refused = new ArrayList<String>();
    final var resolvers = loaded(refused, UNDECIDED, KNOWING);
    assertNull(resolvers.resolve(PLAIN), "the undecided resolver, which would answer Long, is left out");
    assertEquals(String.class, resolvers.resolve(PROXY));
    assertEquals(1, refused.size());
    assertTrue(refused.getFirst().contains(UNDECIDED.getClass().getName()), refused::toString);
  }

  @Test
  @DisplayName("takes a resolver whose classOf throws as giving no answer, and asks the next")
  void takesAThrowingClassOfAsNoAnswer() {
    final var resolvers = loaded(new ArrayList<>(), THROWING, KNOWING);
    assertEquals(String.class, resolvers.resolve(PROXY));
    assertNull(resolvers.resolve(PLAIN));
  }

  @Test
  @DisplayName("is inactive with no resolver, or only inactive ones, so the substrate never asks it")
  void isInactiveWithNoActiveResolver() {
    assertFalse(loaded(new ArrayList<>()).active());
    assertFalse(loaded(new ArrayList<>(), INACTIVE).active());
    assertFalse(new RegisteredProxyResolvers().active(), "core's own tests register no resolver");
  }
}
