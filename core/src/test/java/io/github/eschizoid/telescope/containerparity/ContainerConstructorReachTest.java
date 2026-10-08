package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which constructor builds a container class the shared allocation table does not name: a
 * no-argument one the rebuild can call, or, where the elements pass through unchanged, a copy
 * constructor handed the other side's container. The generated bridge decides by the same rules, so
 * these are the runtime's half of one decision.
 */
class ContainerConstructorReachTest {

  record A(String v) {}

  record B(String v) {}

  public static class PackageBox<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    PackageBox() {}
  }

  public static class ProtectedBox<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    protected ProtectedBox() {}
  }

  public static class PrivateBox<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    private PrivateBox() {}
  }

  public static class CopyOnly<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public CopyOnly(final Collection<? extends E> copied) {
      super(copied);
    }
  }

  record ListOfA(List<A> items) {}

  record PackageBoxOfB(PackageBox<B> items) {}

  record ProtectedBoxOfB(ProtectedBox<B> items) {}

  record PrivateBoxOfB(PrivateBox<B> items) {}

  record ListOfString(List<String> items) {}

  record CopyOnlyOfString(CopyOnly<String> items) {}

  record CopyOnlyOfB(CopyOnly<B> items) {}

  @Test
  @DisplayName("a no-argument constructor that is package-private or protected builds the container")
  void aConstructorOnlyItsPackageCanCallBuildsIt() {
    final var src = new ListOfA(List.of(new A("b"), new A("a")));

    final var packaged = Telescope.mapper(ListOfA.class, PackageBoxOfB.class).forward(src);
    final var protectedOne = Telescope.mapper(ListOfA.class, ProtectedBoxOfB.class).forward(src);

    assertInstanceOf(PackageBox.class, packaged.items(), "the declared class itself, not a stand-in");
    assertEquals(List.of(new B("b"), new B("a")), packaged.items());
    assertInstanceOf(ProtectedBox.class, protectedOne.items());
    assertEquals(List.of(new B("b"), new B("a")), protectedOne.items());
  }

  @Test
  @DisplayName("a private no-argument constructor builds nothing, and the refusal says which class and why")
  void aPrivateConstructorIsRefused() {
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(ListOfA.class, PrivateBoxOfB.class)
    );

    assertTrue(
      thrown
        .getMessage()
        .contains(PrivateBox.class.getCanonicalName() + " has no no-argument constructor a rebuild can call"),
      thrown::getMessage
    );
  }

  @Test
  @DisplayName("a class with only a copy constructor is built by it when its elements pass through, in both directions")
  void aCopyConstructorBuildsAPairWhoseElementsPassThrough() {
    final var mapper = Telescope.mapper(ListOfString.class, CopyOnlyOfString.class);

    final var out = mapper.forward(new ListOfString(List.of("b", "a")));

    assertInstanceOf(CopyOnly.class, out.items(), "built by its own copy constructor");
    assertEquals(List.of("b", "a"), out.items());
    assertEquals(List.of("b", "a"), mapper.backward(out).items());
    // A null container is handed across as null rather than handed to the constructor.
    assertNull(mapper.forward(new ListOfString(null)).items());
  }

  @Test
  @DisplayName("a copy constructor builds nothing when the elements are converted, since there is nothing to hand it")
  void aCopyConstructorCannotBuildConvertedElements() {
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(ListOfA.class, CopyOnlyOfB.class)
    );

    assertTrue(thrown.getMessage().contains("has no no-argument constructor a rebuild can call"), thrown::getMessage);
  }
}
