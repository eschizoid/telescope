package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The substrate's proxy hook: which registration it takes, and what a value's class then is. */
class ProxyClassesTest {

  /** Resolves every {@code ArrayList} to {@code List}, or, inactive, is never asked. */
  private record Fixed(boolean active) implements ProxyClasses {
    @Override
    public Class<?> resolve(final Object value) {
      return value instanceof ArrayList<?> ? List.class : null;
    }
  }

  /**
   * Registrations as a {@code ServiceLoader} yields them, where each {@code null} stands for one it
   * fails on, throwing from {@code next} and moving past it.
   */
  private static Iterator<ProxyClasses> registrations(final ProxyClasses... entries) {
    final var queue = new ArrayList<ProxyClasses>();
    for (final var entry : entries) {
      queue.add(entry);
    }
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return !queue.isEmpty();
      }

      @Override
      public ProxyClasses next() {
        final var entry = queue.removeFirst();
        if (entry == null) {
          throw new ServiceConfigurationError("not a subtype");
        }
        return entry;
      }
    };
  }

  @Test
  @DisplayName("takes the first active registration, passing over an inactive one and one the loader fails on")
  void takesTheFirstActiveRegistration() {
    final var active = new Fixed(true);
    assertSame(active, ProxyClasses.Registered.first(registrations(new Fixed(false), null, active, new Fixed(true))));
  }

  @Test
  @DisplayName("takes none where every registration is inactive or fails, or there is none")
  void takesNoneWithoutAnActiveRegistration() {
    assertNull(ProxyClasses.Registered.first(registrations()));
    assertNull(ProxyClasses.Registered.first(registrations(null, new Fixed(false), null)));
  }

  @Test
  @DisplayName("gives up on a loader that never stops failing")
  void givesUpOnALoaderThatNeverStopsFailing() {
    final Iterator<ProxyClasses> failing = new Iterator<>() {
      @Override
      public boolean hasNext() {
        return true;
      }

      @Override
      public ProxyClasses next() {
        throw new ServiceConfigurationError("not a subtype");
      }
    };
    assertNull(ProxyClasses.Registered.first(failing));
  }

  @Test
  @DisplayName("a value is what the registration resolves it to, and otherwise its own class")
  void aValueIsWhatTheRegistrationResolvesItTo() {
    final var registered = new Fixed(true);
    assertEquals(List.class, ProxyClasses.Registered.classOf(new ArrayList<>(), registered));
    assertEquals(String.class, ProxyClasses.Registered.classOf("plain", registered));
    assertEquals(ArrayList.class, ProxyClasses.Registered.classOf(new ArrayList<>(), null));
  }
}
