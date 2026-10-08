package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.Allocation.Call;
import io.github.eschizoid.telescope.internal.pairing.ContainerView.Kind;
import java.io.Serial;
import java.lang.reflect.Type;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSequentialList;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which class a declared container is rebuilt as, how that class is sized, and whether the code
 * rebuilding it can build it: one decision, which the runtime renders as an allocator and the
 * processor as source text. These pin the decision itself, in the world the runtime reads.
 */
class ContainerAllocationTest {

  private static final ContainerAllocation<Type> ALLOCATION = new ContainerAllocation<>(new ReflectionProps());

  /**
   * The package these fixtures are declared in, as a rebuild generated beside them would name it.
   */
  private static final String HERE = ContainerAllocationTest.class.getPackageName();

  /** A package the fixtures are not in. */
  private static final String ELSEWHERE = "io.github.eschizoid.telescope.elsewhere";

  public static class PublicCtorList<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  public static class PackageCtorList<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    PackageCtorList() {}
  }

  public static class ProtectedCtorList<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    protected ProtectedCtorList() {}
  }

  public static class PrivateCtorList<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    private PrivateCtorList() {}
  }

  public static class CopyOnlyList<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public CopyOnlyList(final Collection<? extends E> copied) {
      super(copied);
    }
  }

  public static class NarrowCopyList<E> extends ArrayList<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public NarrowCopyList(final ArrayList<? extends E> copied) {
      super(copied);
    }
  }

  public static class CopyOnlySortedSet<E> extends TreeSet<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    public CopyOnlySortedSet(final Collection<? extends E> copied) {
      super(copied);
    }
  }

  public interface OwnList<E> extends List<E> {}

  enum Day {
    MON,
  }

  @SuppressWarnings("unused")
  static final class Declarations {

    EnumMap<Day, String> byDay;
  }

  private static Type declared(final String field) {
    try {
      return Declarations.class.getDeclaredField(field).getGenericType();
    } catch (final NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
  }

  @Nested
  @DisplayName("which class is built")
  class WhichClass {

    @Test
    @DisplayName("a declaration the table names is built as the table says, from any package")
    void aTableRowIsTheTableAnswer() {
      final var build = assertInstanceOf(Allocation.Build.class, ALLOCATION.allocate(List.class, Kind.LIST, ELSEWHERE));

      assertEquals("java.util.ArrayList", build.implName());
      assertEquals(Call.COUNT, build.call());
    }

    @Test
    @DisplayName("an abstract type the table does not name is built as its family's default where that is one of it")
    void anAbstractTypeTakesItsFamilyDefault() {
      final var list = assertInstanceOf(
        Allocation.Build.class,
        ALLOCATION.implementationFor(AbstractList.class, Kind.LIST)
      );
      final var set = assertInstanceOf(
        Allocation.Build.class,
        ALLOCATION.implementationFor(AbstractSet.class, Kind.SET)
      );
      final var map = assertInstanceOf(
        Allocation.Build.class,
        ALLOCATION.implementationFor(AbstractMap.class, Kind.MAP_VALUES)
      );

      // The default is whatever the family's root interface is built as, call included, so an
      // abstract list is sized exactly as a List is.
      assertEquals(PairingRules.familyDefault(Kind.LIST), list);
      assertEquals(new Allocation.Build("java.util.ArrayList", Call.COUNT), list);
      assertEquals(new Allocation.Build("java.util.LinkedHashSet", Call.TABLE_FACTORY), set);
      assertEquals(new Allocation.Build("java.util.LinkedHashMap", Call.TABLE_FACTORY), map);
    }

    @Test
    @DisplayName("an abstract type its family default is not one of is refused, naming both")
    void anAbstractTypeTheDefaultDoesNotFitIsRefused() {
      final var sequential = assertInstanceOf(
        Allocation.Refuse.class,
        ALLOCATION.implementationFor(AbstractSequentialList.class, Kind.LIST)
      );
      final var own = assertInstanceOf(Allocation.Refuse.class, ALLOCATION.implementationFor(OwnList.class, Kind.LIST));

      assertEquals(
        PairingMessages.noDefaultImplementation("java.util.AbstractSequentialList", "java.util.ArrayList"),
        sequential.reason()
      );
      assertTrue(own.reason().contains(OwnList.class.getCanonicalName()), own::reason);
    }

    @Test
    @DisplayName("a concrete class the table does not name is built as itself, by the name its source spells")
    void aConcreteClassIsBuiltAsItself() {
      final var build = assertInstanceOf(
        Allocation.Build.class,
        ALLOCATION.implementationFor(PublicCtorList.class, Kind.LIST)
      );

      // The canonical name, which is what a generated `new` has to write for a nested class.
      assertEquals(PublicCtorList.class.getCanonicalName(), build.implName());
      assertEquals(Call.NO_ARG, build.call());
    }

    @Test
    @DisplayName("an EnumMap is built from the key class its declaration names, and refused where it names none")
    void anEnumMapNeedsItsKeyClass() {
      final var keyed = assertInstanceOf(
        Allocation.Build.class,
        ALLOCATION.allocate(declared("byDay"), Kind.MAP_VALUES, ELSEWHERE)
      );
      final var raw = assertInstanceOf(
        Allocation.Refuse.class,
        ALLOCATION.allocate(EnumMap.class, Kind.MAP_VALUES, null)
      );

      assertEquals(new Allocation.Build("java.util.EnumMap", Call.KEY_CLASS), keyed);
      assertEquals(PairingMessages.noKeyClass("java.util.EnumMap"), raw.reason());
    }
  }

  @Nested
  @DisplayName("who can build it")
  class WhoCanBuildIt {

    @Test
    @DisplayName("a public no-argument constructor is called from any package")
    void aPublicConstructorReachesEverywhere() {
      assertInstanceOf(Allocation.Build.class, ALLOCATION.allocate(PublicCtorList.class, Kind.LIST, ELSEWHERE));
    }

    @Test
    @DisplayName(
      "a package-private or protected one is called from its own package and by the runtime, and from nowhere else"
    )
    void aPackageConstructorReachesItsOwnPackage() {
      for (final Class<?> cls : List.<Class<?>>of(PackageCtorList.class, ProtectedCtorList.class)) {
        assertInstanceOf(Allocation.Build.class, ALLOCATION.allocate(cls, Kind.LIST, HERE), cls::getName);
        assertInstanceOf(Allocation.Build.class, ALLOCATION.allocate(cls, Kind.LIST, null), cls::getName);
        final var refused = assertInstanceOf(
          Allocation.Refuse.class,
          ALLOCATION.allocate(cls, Kind.LIST, ELSEWHERE),
          cls::getName
        );
        assertEquals(PairingMessages.noReachableConstructor(cls.getCanonicalName()), refused.reason());
      }
    }

    @Test
    @DisplayName("a private one, or none at all, is called by nothing, the runtime included")
    void aPrivateOrMissingConstructorReachesNothing() {
      assertInstanceOf(Allocation.Refuse.class, ALLOCATION.allocate(PrivateCtorList.class, Kind.LIST, null));
      assertInstanceOf(Allocation.Refuse.class, ALLOCATION.allocate(CopyOnlyList.class, Kind.LIST, null));
    }

    @Test
    @DisplayName("which class is built does not depend on who builds it")
    void theClassIsDecidedWithoutTheConstructor() {
      // A gate that asks which class first and who can build it second has to see a class here: the
      // refusal belongs to the second question, and a field refused for the first names the wrong
      // cause.
      assertInstanceOf(Allocation.Build.class, ALLOCATION.implementationFor(PrivateCtorList.class, Kind.LIST));
    }
  }

  @Nested
  @DisplayName("building by copy constructor")
  class CopyConstructors {

    @Test
    @DisplayName("a class with only a copy constructor is built from a container it accepts")
    void aCopyOnlyClassIsCopied() {
      assertTrue(ALLOCATION.copiesInPlace(CopyOnlyList.class, Kind.LIST, List.class, null));
    }

    @Test
    @DisplayName("a copy constructor narrower than the container handed to it does not count")
    void aNarrowCopyConstructorDoesNotCount() {
      assertFalse(ALLOCATION.copiesInPlace(NarrowCopyList.class, Kind.LIST, List.class, null));
      assertTrue(ALLOCATION.copiesInPlace(NarrowCopyList.class, Kind.LIST, ArrayList.class, null));
    }

    @Test
    @DisplayName("a sorted class is never copied in place of an allocation, since an overload can drop its order")
    void aSortedClassIsNotCopied() {
      assertFalse(ALLOCATION.copiesInPlace(CopyOnlySortedSet.class, Kind.SET, Set.class, null));
    }

    @Test
    @DisplayName("an allocable class is copied where the class it is built as has the constructor, and only then")
    void anAllocableClassNeedsTheConstructorToo() {
      assertTrue(ALLOCATION.copiesInPlace(List.class, Kind.LIST, List.class, null));
      assertFalse(ALLOCATION.copiesInPlace(Stack.class, Kind.LIST, List.class, null), "Stack has no copy constructor");
      // An EnumMap's copy constructor refuses an empty map that is not an EnumMap.
      assertFalse(ALLOCATION.copiesInPlace(declared("byDay"), Kind.MAP_VALUES, Map.class, null));
    }
  }
}
