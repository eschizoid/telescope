package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.Serial;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binding a class's declared no-argument constructor, whatever its access. Which constructors a
 * rebuild may call is decided elsewhere; this is the call, and what it answers where there is
 * nothing to call.
 */
class BeansNoArgConstructorTest {

  public static class PrivateCtor extends ArrayList<String> {

    @Serial
    private static final long serialVersionUID = 1L;

    private PrivateCtor() {
      add("built");
    }
  }

  public static class CapacityOnly extends ArrayList<String> {

    @Serial
    private static final long serialVersionUID = 1L;

    public CapacityOnly(final int capacity) {
      super(capacity);
    }
  }

  /** Abstract, with a public constructor a private lookup would bind and nothing could call. */
  public abstract static class AbstractOwn extends ArrayList<String> {

    @Serial
    private static final long serialVersionUID = 1L;

    public AbstractOwn() {}
  }

  @Test
  @DisplayName("a constructor of any access in a module that opens to telescope is called")
  void aPrivateConstructorIsCalled() {
    final var supplier = Beans.noArgConstructor(PrivateCtor.class);

    assertNotNull(supplier);
    assertEquals(List.of("built"), supplier.get());
    // Bound once per class, so the decision being asked twice costs one binding.
    assertSame(supplier, Beans.noArgConstructor(PrivateCtor.class));
  }

  @Test
  @DisplayName("a public constructor in a module that declines a private lookup is called through the public one")
  void aJavaBaseConstructorIsCalled() {
    assertInstanceOf(ArrayList.class, Beans.noArgConstructor(ArrayList.class).get());
  }

  @Test
  @DisplayName(
    "nothing is bound for an abstract class, for a class with no no-argument constructor, or for a hidden one in a closed module"
  )
  void nothingIsBoundWhereNothingCanBeCalled() throws ClassNotFoundException {
    assertNull(Beans.noArgConstructor(AbstractList.class));
    assertNull(Beans.noArgConstructor(AbstractOwn.class));
    assertNull(Beans.noArgConstructor(List.class));
    assertNull(Beans.noArgConstructor(CapacityOnly.class));
    // A private class of java.util, whose implicit constructor is private: java.base opens to
    // no one, and the public lookup reaches only public constructors.
    assertNull(Beans.noArgConstructor(Class.forName("java.util.Collections$EmptyList")));
  }
}
