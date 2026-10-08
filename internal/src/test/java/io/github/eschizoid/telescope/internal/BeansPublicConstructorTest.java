package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Comparator;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A public constructor is bound wherever the class's no-argument constructor is: through a private
 * lookup in a module that grants one, and through the public lookup in one that does not. Lookups
 * across a named module boundary are exercised in {@link ModuleAccessLayerTest}.
 */
class BeansPublicConstructorTest {

  /** Not public itself, with a public constructor taking a comparator. */
  static final class Secluded<E> extends TreeSet<E> {

    private static final long serialVersionUID = 1L;

    public Secluded(final Comparator<? super E> order) {
      super(order);
    }
  }

  /** Public, with a comparator constructor that is not. */
  public static final class Guarded<E> extends TreeSet<E> {

    private static final long serialVersionUID = 1L;

    Guarded(final Comparator<? super E> order) {
      super(order);
    }
  }

  @Test
  @DisplayName("a public constructor on a class that is not public is bound through a private lookup")
  void aClassThatIsNotPublicIsReached() throws Throwable {
    final var ctor = Beans.publicConstructor(Secluded.class, Comparator.class);

    assertNotNull(ctor);
    final Secluded<?> built = assertInstanceOf(Secluded.class, ctor.invoke(Comparator.reverseOrder()));
    assertEquals(Comparator.reverseOrder(), built.comparator(), "the constructor received the comparator");
  }

  @Test
  @DisplayName("a java.base class, whose module grants no private lookup, is bound through the public one")
  void aClassInAModuleThatDeclinesAPrivateLookupIsReachedPublicly() throws Throwable {
    final var ctor = Beans.publicConstructor(TreeSet.class, Comparator.class);

    assertNotNull(ctor);
    final TreeSet<?> built = assertInstanceOf(TreeSet.class, ctor.invoke(Comparator.reverseOrder()));
    assertEquals(Comparator.reverseOrder(), built.comparator());
  }

  @Test
  @DisplayName("a constructor that is not public is not bound")
  void aConstructorThatIsNotPublicIsNotBound() {
    assertNull(Beans.publicConstructor(Guarded.class, Comparator.class));
  }
}
