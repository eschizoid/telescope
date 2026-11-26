package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.CollectionLinks.BackReference;
import io.github.eschizoid.telescope.internal.pairing.CollectionLinks.Collection;
import io.github.eschizoid.telescope.internal.pairing.CollectionLinks.Method;
import io.github.eschizoid.telescope.internal.pairing.CollectionLinks.Returns;
import io.github.eschizoid.telescope.internal.pairing.CollectionLinks.Source;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The shared decision of how a collection's elements are tied to their owner: which method is the
 * adder or the remover, by the type it takes, then the most specific type, then the name, and which
 * property of the element is the back-reference, only ever one something declares.
 */
class CollectionLinksTest {

  /** What every line is. */
  private static class BaseLine {}

  /** The element of the collections the tests write. */
  private static final class Line extends BaseLine {}

  /** Classes as loaded classes answer about them. */
  private static final CollectionLinks.Classes<Class<?>> CLASSES = new CollectionLinks.Classes<>() {
    @Override
    public boolean assignable(final Class<?> from, final Class<?> to) {
      return to.isAssignableFrom(from);
    }

    @Override
    public boolean isObject(final Class<?> type) {
      return type == Object.class;
    }

    @Override
    public String simpleName(final Class<?> type) {
      return type.getSimpleName();
    }
  };

  private static Method<String, Class<?>> method(final String name, final Class<?> parameter, final Returns returns) {
    return new Method<>(name, name, parameter, returns);
  }

  private static CollectionLinks.Members<String> lines(final List<Method<String, Class<?>>> methods) {
    return lines(methods, true);
  }

  /**
   * The members of {@code Order.lines}, a list the owner has a setter for where {@code settable}.
   */
  private static CollectionLinks.Members<String> lines(
    final List<Method<String, Class<?>>> methods,
    final boolean settable
  ) {
    return CollectionLinks.members(
      "Order",
      List.of(new Collection<>("lines", Line.class, settable)),
      methods,
      CLASSES
    ).get("lines");
  }

  @Nested
  @DisplayName("Which methods are the adder and the remover")
  class Members {

    @Test
    @DisplayName("the one method taking the element is the adder, whatever follows add in its name")
    void theOneCandidateIsTakenWhateverItsName() {
      final var found = lines(
        List.of(
          method("addOrderLine", Line.class, Returns.VOID),
          method("removeOrderLine", Line.class, Returns.BOOLEAN)
        )
      );
      assertEquals("addOrderLine", found.adder());
      assertEquals("removeOrderLine", found.remover());
      assertNull(found.refusal());
      assertNull(found.refusal());
    }

    @Test
    @DisplayName("a setter, a method of another prefix, one returning something else and one taking Object are none")
    void onlyThePrefixTheReturnsAndATypedParameterQualify() {
      final var found = lines(
        List.of(
          method("setLine", Line.class, Returns.VOID),
          method("attach", Line.class, Returns.VOID),
          method("addLine", Line.class, Returns.BOOLEAN),
          method("add", Line.class, Returns.VOID),
          method("addAnything", Object.class, Returns.VOID),
          method("removeLine", Line.class, Returns.OTHER)
        )
      );
      assertNull(found.adder());
      assertNull(found.remover());
    }

    @Test
    @DisplayName("an adder taking a superclass of the element counts, and a fluent one returning the owner too")
    void aWiderParameterAndTheOwnerReturnedCount() {
      assertEquals("addLine", lines(List.of(method("addLine", BaseLine.class, Returns.OWNER))).adder());
    }

    @Test
    @DisplayName("a method taking a superclass of the element and named for another collection is no adder")
    void aWiderMethodNamedForNothingIsNone() {
      final var found = lines(List.of(method("addAttachment", BaseLine.class, Returns.VOID)));
      assertNull(found.adder());
      assertNull(found.refusal());
    }

    @Test
    @DisplayName("the values a write hands to the adder unconverted: records, enums, strings, boxes and JDK values")
    void valuesAreThoseThatCannotBeRepointed() {
      assertTrue(CollectionLinks.isValue("shop.Line", true, false));
      assertTrue(CollectionLinks.isValue("shop.Status", false, true));
      assertTrue(CollectionLinks.isValue("java.lang.String", false, false));
      assertTrue(CollectionLinks.isValue("java.lang.Integer", false, false));
      assertTrue(CollectionLinks.isValue("java.time.LocalDate", false, false));
      assertTrue(CollectionLinks.isValue("java.util.UUID", false, false));
      assertFalse(CollectionLinks.isValue("shop.LineEntity", false, false));
      assertFalse(CollectionLinks.isValue("java.lang.StringBuilder", false, false));
    }

    @Test
    @DisplayName("of an adder taking the element and one taking its superclass, the more specific is taken")
    void theMostSpecificParameterWins() {
      final var found = lines(
        List.of(method("addLine", BaseLine.class, Returns.VOID), method("addSpecialLine", Line.class, Returns.VOID))
      );
      assertEquals("addSpecialLine", found.adder());
      assertNull(found.refusal());
    }

    @Test
    @DisplayName("between equally specific methods, the one named for the collection is taken")
    void theNameSettlesBetweenEquallySpecificCandidates() {
      final var found = lines(
        List.of(method("addGift", Line.class, Returns.VOID), method("addLine", Line.class, Returns.VOID))
      );
      assertEquals("addLine", found.adder());
      assertNull(found.refusal());
    }

    @Test
    @DisplayName("equally specific methods, none named for a collection with a setter, are left unused and warned of")
    void anUnsettledTieIsLeftUnused() {
      final var found = lines(
        List.of(method("addSample", Line.class, Returns.VOID), method("addGift", Line.class, Returns.VOID))
      );
      assertNull(found.adder());
      assertNull(found.refusal());
      assertEquals(
        List.of(
          PairingMessages.collectionMemberUnused(
            "Order",
            "lines",
            "Line",
            "adder",
            List.of("addGift(Line)", "addSample(Line)"),
            "addLine"
          )
        ),
        found.warnings()
      );
      assertEquals(
        "addGift(Line) and addSample(Line) left unused: no name says which belongs to 'lines'",
        found.unused()
      );
    }

    @Test
    @DisplayName(
      "equally specific methods, none named for a collection with no setter, refuse the write with each named"
    )
    void anUnsettledTieWithNoOtherWayInIsRefused() {
      final var found = lines(
        List.of(method("addSample", Line.class, Returns.VOID), method("addGift", Line.class, Returns.VOID)),
        false
      );
      assertNull(found.adder());
      assertEquals(
        PairingMessages.collectionMemberUndecided(
          "Order",
          "lines",
          "Line",
          "adder",
          List.of("addGift(Line)", "addSample(Line)"),
          "addLine"
        ),
        found.refusal()
      );
      assertEquals(List.of(), found.warnings());
    }

    @Test
    @DisplayName("two collections of one element class each take the method named for it, and leave the other's alone")
    void collectionsSharingAnElementAreSettledByName() {
      final var both = CollectionLinks.members(
        "Post",
        List.of(new Collection<>("tags", String.class, true), new Collection<>("aliases", String.class, true)),
        List.of(method("addTag", String.class, Returns.VOID), method("addAlias", String.class, Returns.VOID)),
        CLASSES
      );
      assertEquals("addTag", both.get("tags").adder());
      assertEquals("addAlias", both.get("aliases").adder());
      final var one = CollectionLinks.members(
        "Post",
        List.of(new Collection<>("tags", String.class, true), new Collection<>("aliases", String.class, true)),
        List.of(method("addTag", String.class, Returns.VOID)),
        CLASSES
      );
      assertEquals("addTag", one.get("tags").adder());
      assertNull(one.get("aliases").adder());
      assertNull(one.get("aliases").refusal());
    }

    @Test
    @DisplayName("a remover may return a boolean, and its tie, never a refusal, is decided apart from the adder's")
    void theRemoverIsDecidedOnItsOwn() {
      final var found = lines(
        List.of(
          method("addLine", Line.class, Returns.VOID),
          method("removeGift", Line.class, Returns.BOOLEAN),
          method("removeSample", Line.class, Returns.VOID)
        ),
        false
      );
      assertEquals("addLine", found.adder());
      assertNull(found.remover());
      assertNull(found.refusal());
      assertEquals(
        List.of(
          PairingMessages.collectionMemberUnused(
            "Order",
            "lines",
            "Line",
            "remover",
            List.of("removeGift(Line)", "removeSample(Line)"),
            "removeLine"
          )
        ),
        found.warnings()
      );
    }

    @Test
    @DisplayName("a collection naming no element class has neither")
    void aRawCollectionHasNeither() {
      final var found = CollectionLinks.members(
        "Bag",
        List.of(new Collection<Class<?>>("items", null, true)),
        List.of(method("addItem", Line.class, Returns.VOID)),
        CLASSES
      ).get("items");
      assertNull(found.adder());
      assertNull(found.refusal());
    }

    @Test
    @DisplayName("the names a method is named for a collection under make the property singular")
    void namesMakeThePropertySingular() {
      assertEquals(List.of("addLine", "addLin", "addLines"), CollectionLinks.namesFor("add", "lines"));
      assertEquals(
        List.of("removeCategory", "removeCategorie", "removeCategori", "removeCategories"),
        CollectionLinks.namesFor("remove", "categories")
      );
      assertEquals(List.of("addAddresse", "addAddress", "addAddresses"), CollectionLinks.namesFor("add", "addresses"));
      assertEquals(List.of("addData"), CollectionLinks.namesFor("add", "data"));
    }
  }

  @Nested
  @DisplayName("Which property is the back-reference")
  class BackReferences {

    private static final Set<String> NOTHING_FILLED = Set.of();

    private static BackReference decide(
      final String row,
      final String mappedBy,
      final boolean mappedByWritable,
      final Set<String> filled
    ) {
      return CollectionLinks.backReference(row, mappedBy, mappedByWritable, true, filled::contains);
    }

    @Test
    @DisplayName("a row names it over mappedBy")
    void aRowWins() {
      assertEquals(new BackReference("previous", Source.ROW), decide("previous", "order", true, NOTHING_FILLED));
    }

    @Test
    @DisplayName("mappedBy names it where no row does")
    void mappedByNamesIt() {
      assertEquals(new BackReference("order", Source.MAPPED_BY), decide(null, "order", true, NOTHING_FILLED));
    }

    @Test
    @DisplayName("with neither a row nor mappedBy there is none, whatever properties the element has")
    void nothingDeclaredIsNone() {
      assertEquals(BackReference.NONE, decide(null, null, false, NOTHING_FILLED));
    }

    @Test
    @DisplayName("a mappedBy the element has no setter for, or one the element's conversion fills, is none")
    void anUnwritableOrFilledMappedByIsNone() {
      assertEquals(BackReference.NONE, decide(null, "parent", false, NOTHING_FILLED));
      assertEquals(BackReference.NONE, decide(null, "order", true, Set.of("order")));
    }

    @Test
    @DisplayName("elements handed across as the source holds them are never pointed at the owner")
    void handedAcrossElementsAreNotPointed() {
      assertEquals(BackReference.NONE, CollectionLinks.backReference("order", "order", true, false, name -> false));
    }
  }
}
