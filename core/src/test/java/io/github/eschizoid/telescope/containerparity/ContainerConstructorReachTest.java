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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

  /** A map with only a copy constructor. */
  public static class CopyOnlyMap<K, V> extends LinkedHashMap<K, V> {

    @Serial
    private static final long serialVersionUID = 1L;

    public CopyOnlyMap(final Map<? extends K, ? extends V> copied) {
      super(copied);
    }
  }

  /** A list whose copy constructors say which of them built it. */
  public static class Overloaded<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String builtBy;

    public Overloaded(final Collection<? extends E> copied) {
      super(copied);
      builtBy = "Collection";
    }

    public Overloaded(final List<? extends E> copied) {
      super(copied);
      builtBy = "List";
    }

    public Overloaded(final int capacity) {
      super(capacity);
      builtBy = "int";
    }

    String builtBy() {
      return builtBy;
    }
  }

  /** A list whose copy constructor refuses what it is handed. */
  public static class Refusing<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public Refusing(final Collection<? extends E> copied) {
      throw new IllegalArgumentException("not this one");
    }
  }

  /**
   * A list with a hidden constructor, a copy constructor, and a builder, which counts its builds.
   */
  public static class Built<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    static final AtomicInteger BUILDS = new AtomicInteger();

    private Built() {}

    public Built(final Collection<? extends E> copied) {
      super(copied);
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      public Built<Object> build() {
        BUILDS.incrementAndGet();
        return new Built<>();
      }
    }
  }

  record MapOfString(Map<String, String> items) {}

  record CopyOnlyMapOfString(CopyOnlyMap<String, String> items) {}

  record OverloadedOfString(Overloaded<String> items) {}

  record RefusingOfString(Refusing<String> items) {}

  record BuiltOfString(Built<String> items) {}

  @Test
  @DisplayName("a source with only a copy constructor is rebuilt by it on the way back")
  void aCopyOnlySourceIsCopiedBackward() {
    final var mapper = Telescope.mapper(CopyOnlyOfString.class, ListOfString.class);

    final var back = mapper.backward(new ListOfString(List.of("b", "a")));

    assertInstanceOf(CopyOnly.class, back.items());
    assertEquals(List.of("b", "a"), back.items());
  }

  @Test
  @DisplayName("a map with only a copy constructor is built by it, in both directions")
  void aCopyOnlyMapIsCopied() {
    final var mapper = Telescope.mapper(MapOfString.class, CopyOnlyMapOfString.class);
    final var source = new LinkedHashMap<String, String>();
    source.put("b", "B");
    source.put("a", "A");

    final var out = mapper.forward(new MapOfString(source));

    assertInstanceOf(CopyOnlyMap.class, out.items());
    assertEquals(List.of("b", "a"), List.copyOf(out.items().keySet()));
    assertEquals(source, mapper.backward(out).items());
  }

  @Test
  @DisplayName(
    "of several copy constructors, the narrowest one accepting the source builds it, as Java's overload resolution would"
  )
  void theNarrowestCopyConstructorIsCalled() {
    final var out = Telescope.mapper(ListOfString.class, OverloadedOfString.class).forward(
      new ListOfString(new ArrayList<>(List.of("a")))
    );

    assertEquals("List", out.items().builtBy());
  }

  @Test
  @DisplayName("a copy constructor that throws is reported by name, with what it threw as the cause")
  void aThrowingCopyConstructorIsNamed() {
    final var mapper = Telescope.mapper(ListOfString.class, RefusingOfString.class);

    final var thrown = assertThrows(IllegalStateException.class, () -> mapper.forward(new ListOfString(List.of("a"))));

    assertEquals(
      "Deep map: " + Refusing.class.getCanonicalName() + " refused its copy constructor",
      thrown.getMessage()
    );
    assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
  }

  @Test
  @DisplayName("a side its builder makes is built by the builder, not by its copy constructor")
  void aBuilderBeatsACopyConstructor() {
    final var before = Built.BUILDS.get();

    final var out = Telescope.mapper(ListOfString.class, BuiltOfString.class).forward(
      new ListOfString(List.of("b", "a"))
    );

    assertEquals(List.of("b", "a"), out.items());
    assertTrue(Built.BUILDS.get() > before, "the builder made the container");
  }
}
