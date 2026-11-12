package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.BeanWriteStrategy;
import io.github.eschizoid.telescope.internal.pairing.CollectionWrites;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How {@link Beans} finds a bean's adders and builds the bean through them: which collections an
 * adder fills, which method is the adder, the refusal of a collection that has none, and how the
 * adders writer builds and answers for the properties it writes.
 */
class BeansAddersTest {

  /** A bean whose collection has a getter and an adder that marks what it adds, and no setter. */
  public static class Order {

    private String id;
    private final List<String> lines = new ArrayList<>();
    private final Set<Integer> codes = new LinkedHashSet<>();

    public Order() {}

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public List<String> getLines() {
      return lines;
    }

    public void addLine(final String line) {
      lines.add(line + "!");
    }

    public Set<Integer> getCodes() {
      return codes;
    }

    public Order addCode(final Integer code) {
      codes.add(code);
      return this;
    }

    /** A collection no field stores, which no adder is asked for. */
    public List<String> getDerived() {
      return List.copyOf(lines);
    }

    /** Neither a static method nor one of two parameters is an adder. */
    public static void addLine(final Order order, final String line) {}
  }

  /** An order whose collections are inherited, with the adder declared on the superclass. */
  public static class SpecialOrder extends Order {}

  /** A bean whose collection has a setter and no adder. */
  public static class Adderless {

    private List<String> lines = new ArrayList<>();
    private Map<String, String> labels = Map.of();

    public Adderless() {}

    public List<String> getLines() {
      return lines;
    }

    public void setLines(final List<String> lines) {
      this.lines = lines;
    }

    public Map<String, String> getLabels() {
      return labels;
    }

    public void setLabels(final Map<String, String> labels) {
      this.labels = labels;
    }
  }

  /** A bean with an adder and no no-argument constructor. */
  public static class Constructed {

    private final List<String> lines = new ArrayList<>();

    public Constructed(final String ignored) {}

    public List<String> getLines() {
      return lines;
    }

    public void addLine(final String line) {
      lines.add(line);
    }
  }

  @Nested
  @DisplayName("Adder discovery")
  class Discovery {

    @Test
    @DisplayName("each collection a field stores is filled through the adder its name makes singular")
    void eachStoredCollectionHasItsAdder() {
      assertEquals(Set.of("lines", "codes"), Beans.adders(Order.class).keySet());
      assertNull(Beans.adderRefusal(Order.class));
    }

    @Test
    @DisplayName("a collection no field stores is asked for no adder, and a map never is")
    void derivedCollectionsAndMapsHaveNoAdder() {
      assertFalse(Beans.adders(Order.class).containsKey("derived"));
      assertFalse(Beans.adders(Adderless.class).containsKey("labels"));
    }

    @Test
    @DisplayName("a subclass finds the adders and the fields its superclass declares")
    void aSubclassFindsInheritedAdders() {
      assertEquals(Set.of("lines", "codes"), Beans.adders(SpecialOrder.class).keySet());
    }

    @Test
    @DisplayName("the adder bound is the instance method, whatever it returns, and it runs on the bean it is handed")
    void theBoundAdderCallsTheInstanceMethod() {
      final var order = new Order();
      Beans.adders(Order.class).get("lines").accept(order, "a");
      Beans.adders(Order.class).get("codes").accept(order, 7);
      assertEquals(List.of("a!"), order.getLines());
      assertEquals(Set.of(7), order.getCodes());
    }

    @Test
    @DisplayName("a stored collection with no adder is refused by its name, in the shared words")
    void aMissingAdderIsRefusedByName() {
      assertEquals(
        PairingMessages.adderMissing("Adderless", "lines", "String", CollectionWrites.adderNames("lines")),
        Beans.adderRefusal(Adderless.class)
      );
      assertEquals(Map.of(), Beans.adders(Adderless.class));
    }

    @Test
    @DisplayName("into refills a collection or map property in place, and writes any other through its setter")
    void refilledInPlaceIsTheContainerProperties() {
      assertTrue(Beans.refilledInPlace(Order.class, "lines"));
      assertTrue(Beans.refilledInPlace(Adderless.class, "labels"));
      assertFalse(Beans.refilledInPlace(Order.class, "id"));
    }
  }

  @Nested
  @DisplayName("The adders writer")
  class Writer {

    @Test
    @DisplayName("builds through the no-argument constructor, the adder for a collection and the setter for the rest")
    void buildsThroughAddersAndSetters() {
      final var writer = Beans.addersWriter(Order.class);
      final Map<String, Object> values = Map.of("id", "o", "lines", List.of("a", "b"), "codes", List.of(1));
      final var order = writer.construct(new String[] { "id", "lines", "codes" }, values::get);
      assertEquals("o", order.getId());
      assertEquals(List.of("a!", "b!"), order.getLines());
      assertEquals(Set.of(1), order.getCodes());
    }

    @Test
    @DisplayName("a null collection adds nothing, and the collection keeps what the constructor gave it")
    void aNullCollectionAddsNothing() {
      final var order = Beans.addersWriter(Order.class).construct(new String[] { "lines" }, name -> null);
      assertEquals(List.of(), order.getLines());
    }

    @Test
    @DisplayName("writes every property an adder or a setter takes, and names the strategy it is")
    void writesWhatAnAdderOrSetterTakes() {
      final var writer = Beans.addersWriter(Order.class);
      assertTrue(writer.writes("lines"));
      assertTrue(writer.writes("id"));
      assertFalse(writer.writes("derived"));
      assertEquals(BeanWriteStrategy.ADDERS, Beans.strategyOf(writer));
      assertEquals(BeanWriteStrategy.SETTERS, Beans.strategyOf(Beans.settersWriter(Order.class)));
    }

    @Test
    @DisplayName("no single member takes an adder's collection whole, so nothing is checked against one")
    void anAdderPropertyHasNoWholeMember() {
      final var writer = Beans.addersWriter(Order.class);
      assertNull(Beans.writtenMemberClass(writer, Order.class, "lines"));
      assertNull(Beans.setterRefusal(writer, Order.class, "lines"));
      assertNull(Beans.setterAmbiguity(writer, Order.class, "lines"));
      assertFalse(Beans.writtenOnlyConverted(writer, Order.class, "lines"));
      assertEquals(Beans.memberPropertyType(Order.class, "lines"), Beans.conversionType(writer, Order.class, "lines"));
      assertSame(String.class, Beans.writtenMemberClass(writer, Order.class, "id"));
    }

    @Test
    @DisplayName("is refused when the bean has a collection with no adder")
    void isRefusedForAMissingAdder() {
      final var thrown = assertThrows(IllegalStateException.class, () -> Beans.addersWriter(Adderless.class));
      assertEquals(Beans.adderRefusal(Adderless.class), thrown.getMessage());
    }

    @Test
    @DisplayName("is refused when the bean has no no-argument constructor")
    void isRefusedWithNoNoArgumentConstructor() {
      final var thrown = assertThrows(IllegalStateException.class, () -> Beans.addersWriter(Constructed.class));
      assertEquals(PairingMessages.addersNeedNoArgConstructor("Constructed"), thrown.getMessage());
    }

    @Test
    @DisplayName("is never folded into one composed build, whose setter calls take no adder")
    void isNeverComposed() {
      assertFalse(MhIso.composesBuild(Adderless.class, Order.class, Beans.addersWriter(Order.class)));
    }
  }
}
