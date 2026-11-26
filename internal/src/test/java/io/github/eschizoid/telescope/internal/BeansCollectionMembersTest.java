package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.BeanWriteStrategy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How {@link Beans} finds a bean's adders, removers and back-reference candidates by type, and
 * builds the bean through its adders: which method of the loaded class is each, how each is bound,
 * and how a setters writer narrowed to adders builds and answers for the properties it writes.
 */
class BeansCollectionMembersTest {

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

    public boolean removeCode(final Integer code) {
      return codes.remove(code);
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

  /**
   * A box of items whose adder takes the type variable.
   *
   * @param <T> the item class
   */
  public static class Box<T> {

    private final List<T> items = new ArrayList<>();

    public List<T> getItems() {
      return items;
    }

    public void addItem(final T item) {
      items.add(item);
    }
  }

  /** A box of {@link Line}s. */
  public static class LineBox extends Box<Line> {}

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

  /** A line pointing back at the order that holds it, and at a second one. */
  public static class Line {

    private Order order;
    private Order previous;
    private Order readOnly;

    public Line() {}

    public Order getOrder() {
      return order;
    }

    public void setOrder(final Order order) {
      this.order = order;
    }

    public Order getPrevious() {
      return previous;
    }

    public void setPrevious(final Order previous) {
      this.previous = previous;
    }

    public Order getReadOnly() {
      return readOnly;
    }

    public Object getOwner() {
      return order;
    }

    public void setOwner(final Object owner) {
      this.order = (Order) owner;
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
  @DisplayName("Member discovery")
  class Discovery {

    @Test
    @DisplayName("each collection takes the method taking its element, a fluent adder and a boolean remover included")
    void eachCollectionHasItsMembers() {
      assertEquals("addLine(String)", Beans.collectionMembers(Order.class, "lines").adderSignature());
      assertEquals("addCode(Integer)", Beans.collectionMembers(Order.class, "codes").adderSignature());
      assertEquals("removeCode(Integer)", Beans.collectionMembers(Order.class, "codes").removerSignature());
      assertNull(Beans.collectionMembers(Order.class, "lines").removerSignature());
    }

    @Test
    @DisplayName("a derived collection of the same element leaves the adder to the one it is named for")
    void aDerivedCollectionLeavesTheAdderToItsNamesake() {
      final var derived = Beans.collectionMembers(Order.class, "derived");
      assertNull(derived.adder());
      assertNull(derived.refusal());
    }

    @Test
    @DisplayName("a map, a value and a static method of two parameters are no member of any collection")
    void mapsValuesAndStaticsAreNone() {
      assertNull(Beans.collectionMembers(Adderless.class, "labels").adder());
      assertNull(Beans.collectionMembers(Order.class, "id").adder());
      assertNull(Beans.collectionMembers(Adderless.class, "lines").adder());
    }

    @Test
    @DisplayName("a subclass finds the members its superclass declares")
    void aSubclassFindsInheritedMembers() {
      assertEquals("addLine(String)", Beans.collectionMembers(SpecialOrder.class, "lines").adderSignature());
      assertEquals("removeCode(Integer)", Beans.collectionMembers(SpecialOrder.class, "codes").removerSignature());
    }

    @Test
    @DisplayName("an adder a generic superclass declares over its type variable takes the class the subclass gives it")
    void aGenericAdderTakesTheSubclassesArgument() {
      final var members = Beans.collectionMembers(LineBox.class, "items");
      assertEquals("addItem(Line)", members.adderSignature());
      final var box = new LineBox();
      final var line = new Line();
      members.adder().accept(box, line);
      assertEquals(List.of(line), box.getItems());
    }

    @Test
    @DisplayName("each member is bound as the instance method, whatever it returns, run on the bean it is handed")
    void theBoundMembersCallTheInstanceMethods() {
      final var order = new Order();
      Beans.collectionMembers(Order.class, "lines").adder().accept(order, "a");
      Beans.collectionMembers(Order.class, "codes").adder().accept(order, 7);
      Beans.collectionMembers(Order.class, "codes").adder().accept(order, 8);
      Beans.collectionMembers(Order.class, "codes").remover().accept(order, 7);
      assertEquals(List.of("a!"), order.getLines());
      assertEquals(Set.of(8), order.getCodes());
    }

    @Test
    @DisplayName("the element class of a collection is its declared element, and none for a map or a value")
    void theElementClassOfACollection() {
      assertSame(String.class, Beans.collectionElement(Order.class, "lines"));
      assertSame(Integer.class, Beans.collectionElement(Order.class, "codes"));
      assertNull(Beans.collectionElement(Adderless.class, "labels"));
      assertNull(Beans.collectionElement(Order.class, "id"));
    }

    @Test
    @DisplayName("into refills a collection or map property in place, and writes any other through its setter")
    void refilledInPlaceIsTheContainerProperties() {
      assertTrue(Beans.refilledInPlace(Order.class, "lines"));
      assertTrue(Beans.refilledInPlace(Adderless.class, "labels"));
      assertFalse(Beans.refilledInPlace(Order.class, "id"));
    }
  }

  @Test
  @DisplayName("a setter takes the owner where its parameter is the owner's type or wider")
  void aSetterTakesTheOwner() {
    assertTrue(Beans.setterTakes(Line.class, "order", Order.class));
    assertTrue(Beans.setterTakes(Line.class, "owner", Order.class));
    assertTrue(Beans.setterTakes(Line.class, "order", SpecialOrder.class));
    assertFalse(Beans.setterTakes(Line.class, "readOnly", Order.class));
  }

  @Test
  @DisplayName("a bean is built through its adders where it has a no-argument constructor and an adder")
  void aBeanWithAnAdderIsBuildableThroughIt() {
    assertTrue(Beans.buildableThroughAdders(Order.class));
    assertFalse(Beans.buildableThroughAdders(Adderless.class));
    assertFalse(Beans.buildableThroughAdders(Constructed.class));
  }

  @Nested
  @DisplayName("A setters writer narrowed to adders")
  class Writer {

    private static Beans.BeanWriter<Order> throughAdders() {
      final var lines = Beans.collectionMembers(Order.class, "lines").adder();
      final var codes = Beans.collectionMembers(Order.class, "codes").adder();
      return Beans.throughAdders(Beans.settersWriter(Order.class), Map.of("lines", lines, "codes", codes));
    }

    @Test
    @DisplayName("builds through the no-argument constructor, the adder for a collection and the setter for the rest")
    void buildsThroughAddersAndSetters() {
      final Map<String, Object> values = Map.of("id", "o", "lines", List.of("a", "b"), "codes", List.of(1));
      final var order = throughAdders().construct(new String[] { "id", "lines", "codes" }, values::get);
      assertEquals("o", order.getId());
      assertEquals(List.of("a!", "b!"), order.getLines());
      assertEquals(Set.of(1), order.getCodes());
    }

    @Test
    @DisplayName("a null collection adds nothing, and the collection keeps what the constructor gave it")
    void aNullCollectionAddsNothing() {
      final var order = throughAdders().construct(new String[] { "lines" }, name -> null);
      assertEquals(List.of(), order.getLines());
    }

    @Test
    @DisplayName("writes every property an adder or a setter takes, and is the setters strategy")
    void writesWhatAnAdderOrSetterTakes() {
      final var writer = throughAdders();
      assertTrue(writer.writes("lines"));
      assertTrue(writer.writes("id"));
      assertFalse(writer.writes("derived"));
      assertFalse(Beans.settersWriter(Order.class).writes("lines"));
      assertEquals(BeanWriteStrategy.SETTERS, Beans.strategyOf(writer));
      assertTrue(Beans.fillsThroughAdders(writer));
      assertFalse(Beans.fillsThroughAdders(Beans.settersWriter(Order.class)));
    }

    @Test
    @DisplayName("no adders, or a writer that is no setters writer, comes back as it is")
    void nothingToNarrowComesBackAsItIs() {
      final var setters = Beans.settersWriter(Order.class);
      assertSame(setters, Beans.throughAdders(setters, Map.of()));
      final var constructed = Beans.constructorWriter(Adderless.class, 0);
      assertSame(constructed, Beans.throughAdders(constructed, Map.of("lines", (owner, element) -> {})));
    }

    @Test
    @DisplayName("no single member takes an adder's collection whole, so nothing is checked against one")
    void anAdderPropertyHasNoWholeMember() {
      final var writer = throughAdders();
      assertNull(Beans.writtenMemberClass(writer, Order.class, "lines"));
      assertNull(Beans.setterRefusal(writer, Order.class, "lines"));
      assertNull(Beans.setterAmbiguity(writer, Order.class, "lines"));
      assertFalse(Beans.writtenOnlyConverted(writer, Order.class, "lines"));
      assertEquals(Beans.memberPropertyType(Order.class, "lines"), Beans.conversionType(writer, Order.class, "lines"));
      assertSame(String.class, Beans.writtenMemberClass(writer, Order.class, "id"));
    }

    @Test
    @DisplayName("is never folded into one composed build, whose setter calls take no adder")
    void isNeverComposed() {
      assertFalse(MhIso.composesBuild(Adderless.class, Order.class, throughAdders()));
    }
  }
}
