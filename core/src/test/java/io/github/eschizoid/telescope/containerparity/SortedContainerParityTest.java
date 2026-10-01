package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether a sorted source can be converted is decided twice — once while a bridge is generated and
 * once while a mapper is planned — and the two are meant to accept the same programs. They decide
 * it from the same facts, so a narrowing applied to one and not the other is a conversion that
 * works when written against the runtime and fails when written against {@code @Bridge}.
 *
 * <p>These run the generated bridge rather than reading its text. The decision is emitted as a
 * runtime test inside the generated method, so the file compiles either way and only running it
 * tells the two apart.
 *
 * <p>The element types deliberately implement nothing. A {@code Comparable} element makes every row
 * here pass whatever either path decides, which is the same shape as writing the fixture with an
 * empty set: the container never has to order anything, so the question is never asked.
 */
class SortedContainerParityTest {

  private static Set<SortedParityA> sortedWithComparator() {
    final var byV = new TreeSet<SortedParityA>(Comparator.comparing(SortedParityA::v));
    byV.add(new SortedParityA("x"));
    return byV;
  }

  @Test
  @DisplayName("a comparator no target needs is refused by neither path")
  void unorderedTargetAcceptsOnBothPaths() {
    // The source holds a TreeSet with a custom comparator behind a plain Set declaration, and the
    // target keeps no order. The comparator cannot come across — it orders the type being
    // converted away from — but nothing is asking it to, so neither path has anything to refuse.
    final var src = new SortedParityPlainSrc(sortedWithComparator());

    final var reflective = Telescope.mapper(SortedParityPlainSrc.class, SortedParityPlainTgt.class).forward(src);
    final var generated = SortedParityPlainSrcBridge.BRIDGE.read(src);

    assertEquals(List.of(new SortedParityB("x")), List.copyOf(reflective.items()), "the reflective path converts");
    assertEquals(List.of(new SortedParityB("x")), List.copyOf(generated.items()), "and so does the generated one");
  }

  @Test
  @DisplayName("a comparator the target does need is refused by both paths")
  void orderedTargetRefusesOnBothPaths() {
    // The control, and what stops the row above from being "delete the check". This target keeps an
    // order, the only ordering on offer is written for the type being converted away from, and
    // falling back to natural ordering would reorder the set without saying so.
    final var src = new SortedParitySortedSrc(sortedWithComparator());

    final var reflective = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SortedParitySortedSrc.class, SortedParitySortedTgt.class).forward(src)
    );
    final var generated = assertThrows(IllegalStateException.class, () -> SortedParitySortedSrcBridge.BRIDGE.read(src));

    assertTrue(reflective.getMessage().contains("comparator"), () -> reflective.getMessage());
    assertTrue(generated.getMessage().contains("comparator"), () -> generated.getMessage());
  }

  @Test
  @DisplayName("an ordinary unsorted source converts on both paths, carrying no ordering question")
  void plainSourceConvertsOnBothPaths() {
    // The second control. A change that refused every set, or accepted every set, would satisfy one
    // of the rows above and break this.
    final var src = new SortedParityPlainSrc(Set.of(new SortedParityA("x")));

    assertEquals(
      List.of(new SortedParityB("x")),
      List.copyOf(Telescope.mapper(SortedParityPlainSrc.class, SortedParityPlainTgt.class).forward(src).items())
    );
    assertEquals(List.of(new SortedParityB("x")), List.copyOf(SortedParityPlainSrcBridge.BRIDGE.read(src).items()));
  }

  private static SortedMap<String, SortedParityA> mapOrderedBy(final Comparator<String> order) {
    final var m = new TreeMap<String, SortedParityA>(order);
    m.put("a", new SortedParityA("1"));
    m.put("b", new SortedParityA("2"));
    return m;
  }

  @Test
  @DisplayName("a declared subtype that cannot receive a comparator refuses a custom one on both paths")
  void orderedSubtypeWithoutTheConstructorRefusesOnBothPaths() {
    // A map is the one container whose comparator outlives element conversion, because its keys
    // match on both sides. So it is the one where dropping the comparator produces a container of
    // the right type, the right size and a different order, which no assertion but this one sees.
    final var src = new SortedSubtypeSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SortedSubtypeSrc.class, SortedSubtypePlainTgt.class).forward(src)
    );
    final var generated = assertThrows(IllegalStateException.class, () -> SortedSubtypeSrcBridge.BRIDGE.read(src));

    assertTrue(reflective.getMessage().contains("Comparator"), () -> reflective.getMessage());
    assertTrue(generated.getMessage().contains("Comparator"), () -> generated.getMessage());
  }

  @Test
  @DisplayName("a declared subtype that can receive a comparator keeps the source's order on both paths")
  void orderedSubtypeWithTheConstructorKeepsTheOrderOnBothPaths() {
    final var src = new SortedSubtypeCmpSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = Telescope.mapper(SortedSubtypeCmpSrc.class, SortedSubtypeCmpTgt.class).forward(src);
    final var generated = SortedSubtypeCmpSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("b", "a"), List.copyOf(reflective.items().keySet()), "the reflective path keeps the order");
    assertEquals(List.of("b", "a"), List.copyOf(generated.items().keySet()), "and so does the generated one");
  }

  @Test
  @DisplayName("a naturally ordered source converts into either subtype on both paths")
  void naturallyOrderedSourceConvertsOnBothPaths() {
    // Nothing is dropped when there is no comparator to drop, so the pairing that refuses above is
    // fine here. Taking the comparator route unconditionally would fail this row instead.
    final var src = new SortedSubtypeSrc(mapOrderedBy(null));
    final var cmpSrc = new SortedSubtypeCmpSrc(mapOrderedBy(null));

    assertEquals(
      List.of("a", "b"),
      List.copyOf(Telescope.mapper(SortedSubtypeSrc.class, SortedSubtypePlainTgt.class).forward(src).items().keySet())
    );
    assertEquals(List.of("a", "b"), List.copyOf(SortedSubtypeSrcBridge.BRIDGE.read(src).items().keySet()));
    assertEquals(
      List.of("a", "b"),
      List.copyOf(
        Telescope.mapper(SortedSubtypeCmpSrc.class, SortedSubtypeCmpTgt.class).forward(cmpSrc).items().keySet()
      )
    );
    assertEquals(List.of("a", "b"), List.copyOf(SortedSubtypeCmpSrcBridge.BRIDGE.read(cmpSrc).items().keySet()));
  }

  @Test
  @DisplayName("a naturally ordered source does not reach a comparator constructor that would reject it")
  void naturalOrderingSkipsTheComparatorConstructorOnBothPaths() {
    // Writing the constructor to reject null is the ordinary way to write it. Reaching for it when
    // there is no comparator to pass turns a conversion that worked into one that throws.
    final var src = new SortedSubtypeStrictSrc(mapOrderedBy(null));

    final var reflective = Telescope.mapper(SortedSubtypeStrictSrc.class, SortedSubtypeStrictTgt.class).forward(src);
    final var generated = SortedSubtypeStrictSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("a", "b"), List.copyOf(reflective.items().keySet()), "the reflective path converts");
    assertEquals(List.of("a", "b"), List.copyOf(generated.items().keySet()), "and so does the generated one");
  }

  @Test
  @DisplayName("a comparator constructor on a class that cannot be named counts on neither path")
  void anUnnameableClassIsNotAComparatorRouteOnEitherPath() {
    // The constructor is public and the class is not, so only one of the two can be reached. The
    // generated path emits a call by name and the reflective path binds through a public lookup,
    // and both are stopped by the class rather than by the constructor.
    final var src = new SortedSubtypeHiddenSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SortedSubtypeHiddenSrc.class, SortedSubtypeHiddenTgt.class).forward(src)
    );
    final var generated = assertThrows(IllegalStateException.class, () ->
      SortedSubtypeHiddenSrcBridge.BRIDGE.read(src)
    );

    assertTrue(reflective.getMessage().contains("Comparator"), () -> reflective.getMessage());
    assertTrue(generated.getMessage().contains("Comparator"), () -> generated.getMessage());
  }

  @Test
  @DisplayName("a constructor taking Object is not a comparator constructor on either path")
  void anObjectConstructorIsNotAComparatorRouteOnEitherPath() {
    // A comparator can be handed to it, and it is under no obligation to order anything by what it
    // receives. Counting it would produce a container that took the argument and ignored it.
    final var src = new SortedSubtypeObjArgSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(SortedSubtypeObjArgSrc.class, SortedSubtypeObjArgTgt.class).forward(src)
    );
    final var generated = assertThrows(IllegalStateException.class, () ->
      SortedSubtypeObjArgSrcBridge.BRIDGE.read(src)
    );

    assertTrue(reflective.getMessage().contains("Comparator"), () -> reflective.getMessage());
    assertTrue(generated.getMessage().contains("Comparator"), () -> generated.getMessage());
  }

  @Test
  @DisplayName("a plain declaration holding an ordered value is decided the same way by both paths")
  void aPlainDeclarationHoldingAnOrderedValueAgreesOnBothPaths() {
    // The declared type is the weaker of the two facts. A field written as a plain Map can hold a
    // TreeMap ordered by a comparator, and a rebuild that reads only the declaration cannot know
    // whether there is an order to lose.
    final var src = new PlainDeclSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(PlainDeclSrc.class, PlainDeclTgt.class).forward(src)
    );
    final var generated = assertThrows(IllegalStateException.class, () -> PlainDeclSrcBridge.BRIDGE.read(src));

    assertTrue(reflective.getMessage().contains("Comparator"), () -> reflective.getMessage());
    assertTrue(generated.getMessage().contains("Comparator"), () -> generated.getMessage());
  }

  @Test
  @DisplayName("a plain declaration holding an unordered value converts on both paths")
  void aPlainDeclarationHoldingAnUnorderedValueAgreesOnBothPaths() {
    // The control. Reading the declaration alone would refuse this too, and reading the value
    // alone is what tells the two rows apart.
    final var src = new PlainDeclSrc(new LinkedHashMap<>(mapOrderedBy(null)));

    final var reflective = Telescope.mapper(PlainDeclSrc.class, PlainDeclTgt.class).forward(src);
    final var generated = PlainDeclSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("a", "b"), List.copyOf(reflective.items().keySet()), "the reflective path converts");
    assertEquals(List.of("a", "b"), List.copyOf(generated.items().keySet()), "and so does the generated one");
  }

  @Test
  @DisplayName("a plain declaration into a subtype that can be told keeps the order on both paths")
  void aPlainDeclarationIntoASubtypeThatCanBeToldKeepsTheOrderOnBothPaths() {
    // The row where the two facts point opposite ways: the declaration promises no order, and the
    // target can receive one. Refusing on the declaration alone rejects a program whose value has
    // an order the target could have held, and the other path accepts it.
    final var src = new PlainToCmpSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = Telescope.mapper(PlainToCmpSrc.class, PlainToCmpTgt.class).forward(src);
    final var generated = PlainToCmpSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("b", "a"), List.copyOf(reflective.items().keySet()), "the reflective path keeps the order");
    assertEquals(List.of("b", "a"), List.copyOf(generated.items().keySet()), "and so does the generated one");
  }

  @Test
  @DisplayName("a comparator constructor naming the key type exactly is still handed the order, on both paths")
  void anInvariantComparatorParameterStillReceivesTheOrder() {
    // A constructor may write Comparator<K> where the JDK writes Comparator<? super K>. Both hold a
    // comparator over the key type, and only the second holds one over a supertype, so a rebuild
    // reading the order through a wildcard has to narrow for the first. Getting that wrong breaks
    // the adopter's build inside a file they did not write, which is worse than any divergence.
    final var src = new InvCmpSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = Telescope.mapper(InvCmpSrc.class, InvCmpTgt.class).forward(src);
    final var generated = InvCmpSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("b", "a"), List.copyOf(reflective.items().keySet()), "the reflective path keeps the order");
    assertEquals(List.of("b", "a"), List.copyOf(generated.items().keySet()), "and so does the generated one");
  }

  @Test
  @DisplayName("a same-kind copy carries the order on both paths, as an element-wise conversion does")
  void aWholesaleCopyKeepsTheOrderOnBothPaths() {
    // Two classes of the same kind holding the same element type: nothing converts, so the pair is
    // copied whole rather than element by element. Declaring one class both sides would hand the
    // field over by reference and allocate nothing at all. The route that does the copying
    // allocates from a supplier that is handed no source, which is a way to lose an ordering
    // without any element being touched.
    final var ordered = new TreeMap<String, String>(Comparator.reverseOrder());
    ordered.put("a", "1");
    ordered.put("b", "2");
    final var src = new CopyRouteSrc(new CopyRouteCmp(Comparator.<String>reverseOrder()));
    src.items().putAll(ordered);

    final var reflective = Telescope.mapper(CopyRouteSrc.class, CopyRouteTgt.class).forward(src);
    final var generated = CopyRouteSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("b", "a"), List.copyOf(reflective.items().keySet()), "the reflective path keeps the order");
    assertEquals(List.of("b", "a"), List.copyOf(generated.items().keySet()), "and so does the generated one");
  }

  @Test
  @DisplayName("a comparator constructor naming a supertype of the elements receives the order, on both paths")
  void aComparatorParameterWiderThanTheElementsStillReceivesTheOrder() {
    // Between the two shapes the JDK and an adopter write lies every named supertype: a comparator
    // over Object orders anything, and one over CharSequence orders a container of them. All of
    // them
    // can hold a comparator over the elements, so a rule that lists shapes rather than asking
    // whether the parameter can hold one refuses a program that works.
    final var src = new ObjCmpSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = Telescope.mapper(ObjCmpSrc.class, ObjCmpTgt.class).forward(src);
    final var generated = ObjCmpSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("b", "a"), List.copyOf(reflective.items().keySet()), "the reflective path keeps the order");
    assertEquals(List.of("b", "a"), List.copyOf(generated.items().keySet()), "and so does the generated one");
  }

  @Test
  @DisplayName("a comparator parameter over a generic supertype receives the order, on both paths")
  void aGenericSupertypeParameterReceivesTheOrder() {
    // A parameter can name a supertype that is itself generic, and Comparable<?> is one a key type
    // satisfies. Deciding from the shape of the argument rather than from what it mentions refuses
    // a
    // program the other path performs, and says the class declares no such constructor.
    final var src = new ComparableCmpSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = Telescope.mapper(ComparableCmpSrc.class, ComparableCmpTgt.class).forward(src);
    final var generated = ComparableCmpSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("b", "a"), List.copyOf(reflective.items().keySet()), "the reflective path keeps the order");
    assertEquals(List.of("b", "a"), List.copyOf(generated.items().keySet()), "and so does the generated one");
  }

  @Test
  @DisplayName("an entry comparator is refused in either spelling, on both paths")
  void anEntryComparatorIsRefusedBehindAWildcardToo() {
    // The same unusable parameter as the invariant case, written with the wildcard the JDK uses for
    // its own comparator parameters. Reading only the argument's shape waves every wildcard
    // through,
    // and the rebuild then dies inside the adopter's container with nothing about comparators in
    // it.
    final var src = new WildcardEntryCmpSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(WildcardEntryCmpSrc.class, WildcardEntryCmpTgt.class).forward(src)
    );
    final var generated = assertThrows(IllegalStateException.class, () -> WildcardEntryCmpSrcBridge.BRIDGE.read(src));

    assertEquals(reflective.getMessage(), generated.getMessage(), "and refuse in the same words");
  }

  @Test
  @DisplayName("a comparator constructor over the wrong thing is no route on either path")
  void aComparatorConstructorOverSomethingElseIsRefusedOnBothPaths() {
    // A comparator over the entries erases to the same constructor as one over the keys, and can
    // receive nothing a source carries. Reading the bound parameter rather than the declared one
    // makes one path emit code that does not compile and the other reorder in silence.
    final var src = new EntryCmpSrc(mapOrderedBy(Comparator.reverseOrder()));

    final var reflective = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(EntryCmpSrc.class, EntryCmpTgt.class).forward(src)
    );
    final var generated = assertThrows(IllegalStateException.class, () -> EntryCmpSrcBridge.BRIDGE.read(src));

    assertTrue(reflective.getMessage().contains("Comparator"), reflective::getMessage);
    assertEquals(reflective.getMessage(), generated.getMessage(), "and refuse in the same words");
  }

  @Test
  @DisplayName("the set family's wholesale copy carries the order too, on both paths")
  void aWholesaleSetCopyKeepsTheOrderOnBothPaths() {
    // The two families reach their allocator through different code, and only the map side of the
    // copy route was asked. A set copied whole is the shape where an ordering goes missing with no
    // element touched and nothing about the result's size or contents to show it.
    final var src = new CopySetSrc(new CopySetCmp(Comparator.<String>reverseOrder()));
    src.items().add("a");
    src.items().add("b");

    final var reflective = Telescope.mapper(CopySetSrc.class, CopySetTgt.class).forward(src);
    final var generated = CopySetSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("b", "a"), List.copyOf(reflective.items()), "the reflective path keeps the order");
    assertEquals(List.of("b", "a"), List.copyOf(generated.items()), "and so does the generated one");
  }
}
