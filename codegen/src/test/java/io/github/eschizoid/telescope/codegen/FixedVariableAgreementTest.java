package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the compile-time verifier and mapper construction to one answer for a property one side
 * passes a type variable through to and the other side's class fixes: both build the mapper where
 * every value the sending side holds fits, or can be checked as it is written, and both refuse it
 * with the same message where a value given type arguments cannot be shown to fit. A mapper that
 * runs forward only owes nothing to the source.
 *
 * <p>Each case compiles its sources once with the verifier reporting warnings, which leaves the
 * classes to run, then calls the holder's {@code build()}. Every case names its classes with a
 * prefix of its own, because one loader holds them all.
 */
class FixedVariableAgreementTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";
  private static final String WARN = "-Atelescope.verify=warn";

  /**
   * Beans over a superclass whose {@code key} and {@code first} are type variables, and over one
   * whose {@code items} is a list of its variable, each with subclasses that pass a variable of
   * their own through, bound it, or fix it; and a bean with a method that reads no property.
   */
  private static final String SHAPES = """
    package io.github.eschizoid.telescope.codegen;
    import static io.github.eschizoid.telescope.mapping.Mapping.*;
    import io.github.eschizoid.telescope.Telescope;
    import io.github.eschizoid.telescope.mapping.Mapping;
    import java.util.List;
    import java.util.Map;
    import java.util.Set;
    class @@Base<K, V> {
      private K key;
      private V first;
      public @@Base() {}
      public K getKey() { return key; }
      public void setKey(K key) { this.key = key; }
      public V getFirst() { return first; }
      public void setFirst(V first) { this.first = first; }
    }
    class @@Src<X> extends @@Base<String, X> { public @@Src() {} }
    class @@Tgt extends @@Base<String, String> { public @@Tgt() {} }
    class @@Page<T extends Integer> extends @@Base<String, T> { public @@Page() {} }
    class @@NumTgt extends @@Base<String, Number> { public @@NumTgt() {} }
    class @@ListBase<V> {
      private List<V> items;
      public @@ListBase() {}
      public List<V> getItems() { return items; }
      public void setItems(List<V> items) { this.items = items; }
    }
    class @@PassingList<X> extends @@ListBase<X> { public @@PassingList() {} }
    class @@FixedList extends @@ListBase<String> { public @@FixedList() {} }
    class @@PageList<T extends Integer> extends @@ListBase<T> { public @@PageList() {} }
    class @@NumList extends @@ListBase<Number> { public @@NumList() {} }
    @SuppressWarnings("rawtypes") record @@Outer(@@Src v) {}
    record @@OuterDto(@@Tgt v) {}
    @SuppressWarnings("rawtypes") record @@ListOuter(@@PassingList v) {}
    record @@ListOuterDto(@@FixedList v) {}
    class @@FixedGen<Y> extends @@ListBase<String> { public @@FixedGen() {} }
    record @@GenOuter(@@PassingList<String> v) {}
    record @@GenOuterDto(@@FixedGen<String> v) {}
    class @@Fluent {
      private String key;
      public @@Fluent() {}
      public Integer code() { return 1; }
      public String getKey() { return key; }
      public void setKey(String key) { this.key = key; }
    }
    class @@WBase<V> {
      private List<? extends V> items;
      public @@WBase() {}
      public List<? extends V> getItems() { return items; }
      public void setItems(List<? extends V> items) { this.items = items; }
    }
    class @@WSrc<X> extends @@WBase<X> { public @@WSrc() {} }
    class @@WAny {
      private List<?> items;
      public @@WAny() {}
      public List<?> getItems() { return items; }
      public void setItems(List<?> items) { this.items = items; }
    }
    record @@Box<T>(T v) {}
    record @@IntBox(Integer v) {}
    record @@RSrc<X>(String key, X first) {}
    class @@OvSrc<X> extends @@Base<String, X> {
      public @@OvSrc() {}
      @Override public X getFirst() { return super.getFirst(); }
    }
    interface @@HasId<I> { I getId(); }
    class @@ISrc<X> implements @@HasId<X> {
      private X id;
      public @@ISrc() {}
      public X getId() { return id; }
      public void setId(X id) { this.id = id; }
    }
    class @@ITgt implements @@HasId<String> {
      private String id;
      public @@ITgt() {}
      public String getId() { return id; }
      public void setId(String id) { this.id = id; }
    }
    final class @@Nest {
      static final class Item<V> {}
    }
    class @@MBase<V> {
      private Map<String, @@Nest.Item<V>> m;
      public @@MBase() {}
      public Map<String, @@Nest.Item<V>> getM() { return m; }
      public void setM(Map<String, @@Nest.Item<V>> m) { this.m = m; }
    }
    class @@MSrc<X> extends @@MBase<X> { public @@MSrc() {} }
    class @@MTgt extends @@MBase<String> { public @@MTgt() {} }
    class @@MBox<B> {
      private B b;
      public @@MBox() {}
      public B getB() { return b; }
      public void setB(B b) { this.b = b; }
    }
    class @@BoxPage<T extends Integer> extends @@Base<String, @@MBox<T>> { public @@BoxPage() {} }
    class @@BoxNum extends @@Base<String, @@MBox<Number>> { public @@BoxNum() {} }
    class @@BoxAny extends @@Base<String, @@MBox<? extends Number>> { public @@BoxAny() {} }
    class @@HeldList<T extends Integer> extends @@Base<String, List<T>> { public @@HeldList() {} }
    class @@SetBase<V> {
      private Set<V> items;
      public @@SetBase() {}
      public Set<V> getItems() { return items; }
      public void setItems(Set<V> items) { this.items = items; }
    }
    class @@PageSet<T extends Integer> extends @@SetBase<T> { public @@PageSet() {} }
    class @@NumSet extends @@SetBase<Number> { public @@NumSet() {} }
    class @@HeldNumList extends @@Base<String, List<Number>> { public @@HeldNumList() {} }
    final class @@Samples {
      static @@Src<String> src() {
        final var s = new @@Src<String>();
        s.setKey("k");
        s.setFirst("a");
        return s;
      }
    }
    """;

  private record Outcome(List<String> verifier, String construction, Object built) {}

  /**
   * Compiles {@link #SHAPES} and a holder whose {@code build()} returns {@code call} with the
   * verifier reporting warnings, defines what it compiled, and calls {@code build()}: the
   * verifier's reports, and the message construction refused the mapper with, or what it built.
   */
  private static Outcome outcome(final String prefix, final String call) {
    final var code = SHAPES + "class @@Holder { static Object build() { return " + call + "; } }\n";
    final var compilation = compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of(WARN),
      source(PACKAGE + "." + prefix + "Holder", code.replace("@@", prefix))
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var reported = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .toList();
    final var holder = compilation.define(MethodHandles.lookup()).get(PACKAGE + "." + prefix + "Holder");
    try {
      return new Outcome(reported, null, holder.getDeclaredMethod("build").invoke(null));
    } catch (final InvocationTargetException e) {
      return new Outcome(reported, e.getCause().getMessage(), null);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void assertBothRefuseWith(final String owed, final Outcome outcome) {
    assertNotNull(outcome.construction(), "construction built the mapper");
    assertEquals(owed, outcome.construction());
    assertEquals(List.of(owed), outcome.verifier());
  }

  private static void assertBothAccept(final Outcome outcome) {
    assertNull(outcome.construction(), outcome::construction);
    assertEquals(List.of(), outcome.verifier());
  }

  @Nested
  @DisplayName("A value every value of the sending side fits, or that is checked as it is written, is accepted by both")
  class Accepted {

    @Test
    @DisplayName("a variable the source passes through into a property the target fixes to String")
    void passedThroughIntoAFixedProperty() throws ReflectiveOperationException {
      final var outcome = outcome("FvA", "Telescope.mapper(@@Src.class, @@Tgt.class).forward(@@Samples.src())");
      assertBothAccept(outcome);
      assertEquals("k", outcome.built().getClass().getMethod("getKey").invoke(outcome.built()));
      assertEquals("a", outcome.built().getClass().getMethod("getFirst").invoke(outcome.built()));
    }

    @Test
    @DisplayName("Telescope.map over the same pair")
    void mapOverThePair() {
      assertBothAccept(outcome("FvB", "Telescope.map(@@Src.class, @@Tgt.class)"));
    }

    @Test
    @DisplayName("a variable bounded by Integer into a property the target fixes to Number")
    void boundedVariableIntoAWiderFixedProperty() {
      assertBothAccept(outcome("FvC", "Telescope.mapper(@@Page.class, @@NumTgt.class)"));
    }

    @Test
    @DisplayName("a list of a variable bounded by Integer into one fixed to Number, forward only")
    void boundedListForwardOnly() {
      assertBothAccept(
        outcome(
          "FvD",
          "Telescope.mapperForward(@@PageList.class, @@NumList.class, " +
            "Mapping.<@@PageList<?>, @@NumList, List<? extends Number>>to(@@PageList::getItems, @@NumList::getItems))"
        )
      );
    }

    @Test
    @DisplayName("a fixed list into a side passing its variable through, forward only")
    void fixedListIntoAPassingSideForwardOnly() {
      assertBothAccept(
        outcome(
          "FvE",
          "Telescope.mapperForward(@@FixedList.class, @@PassingList.class, " +
            "to(@@FixedList::getItems, @@PassingList<String>::getItems))"
        )
      );
    }

    @Test
    @DisplayName("the pair as a nested pair, the source used raw")
    void nestedPair() {
      assertBothAccept(outcome("FvF", "Telescope.mapper(@@Outer.class, @@OuterDto.class)"));
    }

    @Test
    @DisplayName(
      "a mutable box of a variable bounded by Integer into one the target holds through a wildcard, forward only"
    )
    void sharedBoxIntoAContainingWildcardForwardOnly() {
      assertBothAccept(
        outcome(
          "FvG",
          "Telescope.mapperForward(@@BoxPage.class, @@BoxAny.class, " +
            "Mapping.<@@BoxPage<?>, @@BoxAny, @@MBox<? extends Number>>to(@@BoxPage::getFirst, @@BoxAny::getFirst))"
        )
      );
    }
  }

  @Test
  @DisplayName("a row whose accessor names no property is refused by both, with one message")
  void rowNamingNoPropertyIsRefusedByBoth() {
    final var outcome = outcome(
      "FlA",
      "Telescope.mapperForward(@@Tgt.class, @@Fluent.class, to(@@Tgt::getKey, @@Fluent::code))"
    );
    assertEquals("No getter for property 'code' on " + PACKAGE + ".FlAFluent", outcome.construction());
    assertEquals(List.of(outcome.construction()), outcome.verifier());
  }

  @Test
  @DisplayName("a row whose source accessor names no property is refused by both the same way")
  void rowReadingNoSourcePropertyIsRefusedByBoth() {
    final var outcome = outcome(
      "FlB",
      "Telescope.mapperForward(@@Fluent.class, @@NumTgt.class, to(@@Fluent::code, @@NumTgt::getFirst))"
    );
    assertEquals("No getter for property 'code' on " + PACKAGE + ".FlBFluent", outcome.construction());
    assertEquals(List.of(outcome.construction()), outcome.verifier());
  }

  @Nested
  @DisplayName("A pair read as declared whose two sides declare different types is refused by both, with one message")
  class DeclaredApart {

    @Test
    @DisplayName("a list of values that fit a variable against a list of anything")
    void boundedWildcardAgainstAnUnboundedOne() {
      assertBothRefuseWith(
        PairingMessages.incompatibleShapes("items[*]", "? extends V", "?"),
        outcome("FdA", "Telescope.mapper(@@WSrc.class, @@WAny.class)")
      );
    }

    @Test
    @DisplayName("a generic record's variable against a record's Integer")
    void recordVariableAgainstAFixedRecord() {
      assertBothRefuseWith(
        PairingMessages.incompatibleShapes("v", "T", "java.lang.Integer"),
        outcome("FdB", "Telescope.mapper(@@Box.class, @@IntBox.class)")
      );
    }

    @Test
    @DisplayName("a generic record's variable against the variable a bean's superclass declares")
    void recordVariableAgainstABeanSuperclassVariable() {
      assertBothRefuseWith(
        PairingMessages.incompatibleShapes("first", "X", "V"),
        outcome("FdC", "Telescope.mapper(@@RSrc.class, @@Tgt.class)")
      );
    }

    @Test
    @DisplayName("renaming rows that cross two of the superclass's variables")
    void renamingRowsAcrossVariables() {
      assertBothRefuseWith(
        PairingMessages.incompatibleShapes("first → key", "V", "K"),
        outcome(
          "FdD",
          "Telescope.mapper(@@Src.class, @@Tgt.class, " +
            "to(@@Src<String>::getFirst, @@Tgt::getKey), to(@@Src<String>::getKey, @@Tgt::getFirst))"
        )
      );
    }

    @Test
    @DisplayName("a getter a subclass overrides to return its own variable")
    void overridingGetter() {
      assertBothRefuseWith(
        PairingMessages.incompatibleShapes("first", "X", "V"),
        outcome("FdE", "Telescope.mapper(@@OvSrc.class, @@Tgt.class)")
      );
    }

    @Test
    @DisplayName("a getter a generic interface declares, implemented with a variable and with String")
    void genericInterfaceGetter() {
      assertBothRefuseWith(
        PairingMessages.incompatibleShapes("id", "X", "java.lang.String"),
        outcome("FdF", "Telescope.mapper(@@ISrc.class, @@ITgt.class)")
      );
    }
  }

  @Nested
  @DisplayName("A value given type arguments that cannot be shown to fit is refused by both, with one message")
  class Refused {

    @Test
    @DisplayName("a List<X> into a list the target fixes to String")
    void passedThroughListIntoAFixedList() {
      assertBothRefuseWith(
        PairingMessages.fixedArgumentsDoNotFit("items", "java.util.List<X>", "java.util.List<java.lang.String>"),
        outcome("FrA", "Telescope.mapper(@@PassingList.class, @@FixedList.class)")
      );
    }

    @Test
    @DisplayName("a fixed list a two-way mapper writes back into a side passing its variable through")
    void fixedListWrittenBackIntoAPassingSide() {
      assertBothRefuseWith(
        PairingMessages.fixedArgumentsDoNotFit(
          "items → items",
          "java.util.List<X>",
          "java.util.List<java.lang.String>"
        ),
        outcome(
          "FrB",
          "Telescope.mapper(@@FixedList.class, @@PassingList.class, to(@@FixedList::getItems, @@PassingList<String>::getItems))"
        )
      );
    }

    @Test
    @DisplayName("a list fixed to Number written back into a list of a variable bounded by Integer")
    void numberListWrittenBackIntoABoundedList() {
      assertBothRefuseWith(
        PairingMessages.fixedArgumentsDoNotFit("items", "java.util.List<java.lang.Number>", "java.util.List<T>"),
        outcome("FrC", "Telescope.mapper(@@PageList.class, @@NumList.class)")
      );
    }

    @Test
    @DisplayName("the pair as a nested pair, the source used raw")
    void nestedPair() {
      assertBothRefuseWith(
        PairingMessages.fixedArgumentsDoNotFit("items", "java.util.List<X>", "java.util.List<java.lang.String>"),
        outcome("FrD", "Telescope.mapper(@@ListOuter.class, @@ListOuterDto.class)")
      );
    }

    @Test
    @DisplayName("a nested pair of generic classes given one type argument, whose target's class fixes the list")
    void nestedGenericPairGivenOneArgument() {
      assertBothRefuseWith(
        PairingMessages.fixedArgumentsDoNotFit("items", "java.util.List<X>", "java.util.List<java.lang.String>"),
        outcome("FrF", "Telescope.mapper(@@GenOuter.class, @@GenOuterDto.class)")
      );
    }

    @Test
    @DisplayName("a mutable box both sides hold, whose argument the target fixes wider")
    void sharedBoxIntoAWiderArgument() {
      assertBothRefuseWith(
        PairingMessages.fixedArgumentsDoNotFit(
          "first",
          PACKAGE + ".FrHMBox<T>",
          PACKAGE + ".FrHMBox<java.lang.Number>"
        ),
        outcome("FrH", "Telescope.mapper(@@BoxPage.class, @@BoxNum.class)")
      );
    }

    @Test
    @DisplayName("a list handed across as the variable it fills, which no copy takes, held to its own argument")
    void listFillingAVariableIsShared() {
      assertBothRefuseWith(
        PairingMessages.fixedArgumentsDoNotFit("first", "java.util.List<T>", "java.util.List<java.lang.Number>"),
        outcome("FrJ", "Telescope.mapper(@@HeldList.class, @@HeldNumList.class)")
      );
    }

    @Test
    @DisplayName("a set whose copy can keep the source's comparator, named for that reason")
    void orderedCopy() {
      assertBothRefuseWith(
        PairingMessages.orderedCopyArgumentDiffers("items", "java.util.Set<T>", "java.util.Set<java.lang.Number>"),
        outcome("FrK", "Telescope.mapper(@@PageSet.class, @@NumSet.class)")
      );
    }

    @Test
    @DisplayName("the same box forward only, through a row naming the property")
    void sharedBoxIntoAWiderArgumentForwardOnly() {
      assertBothRefuseWith(
        PairingMessages.fixedArgumentsDoNotFit(
          "first → first",
          PACKAGE + ".FrIMBox<T>",
          PACKAGE + ".FrIMBox<java.lang.Number>"
        ),
        outcome(
          "FrI",
          "Telescope.mapperForward(@@BoxPage.class, @@BoxNum.class, " +
            "Mapping.<@@BoxPage<?>, @@BoxNum, @@MBox<? extends Number>>to(@@BoxPage::getFirst, @@BoxNum::getFirst))"
        )
      );
    }

    @Test
    @DisplayName("a map of a nested generic class, named alike on both paths")
    void mapOfANestedClass() {
      final var item = PACKAGE + ".FrGNest$Item";
      assertBothRefuseWith(
        PairingMessages.fixedArgumentsDoNotFit(
          "m",
          "java.util.Map<java.lang.String, " + item + "<X>>",
          "java.util.Map<java.lang.String, " + item + "<java.lang.String>>"
        ),
        outcome("FrG", "Telescope.mapper(@@MSrc.class, @@MTgt.class)")
      );
    }

    @Test
    @DisplayName("under the default mode the verifier fails the build")
    void defaultModeFailsTheBuild() {
      final var compilation = compileFully(
        List.of(new MapperVerifierProcessor()),
        List.of(),
        source(
          PACKAGE + ".FrEHolder",
          (
            SHAPES +
            "class @@Holder { static Object build() { return Telescope.mapper(@@PassingList.class, @@FixedList.class); } }\n"
          ).replace("@@", "FrE")
        )
      );
      assertFalse(compilation.success());
      assertTrue(
        compilation.hasError(
          PairingMessages.fixedArgumentsDoNotFit("items", "java.util.List<X>", "java.util.List<java.lang.String>")
        ),
        compilation::errorMessages
      );
    }
  }
}
