package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.SETTERS;
import static io.github.eschizoid.telescope.mapping.Mapping.writeBeans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins how {@code Mapper.into} writes a container property: it refills the container the target
 * holds, writes a new one through the setter where the target holds none, and refuses one that
 * cannot be changed; and how the adders strategy fills a collection through its adder, on {@code
 * forward} and on {@code into}.
 */
class MapperIntoCollectionTest {

  record Bag(String name, List<String> tags, Set<String> codes, Map<String, Integer> counts) {}

  public static class BagEntity {

    private String name;
    private List<String> tags = new ArrayList<>();
    private Set<String> codes = new LinkedHashSet<>();
    private Map<String, Integer> counts = new HashMap<>();

    public BagEntity() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(final List<String> tags) {
      this.tags = tags;
    }

    public Set<String> getCodes() {
      return codes;
    }

    public void setCodes(final Set<String> codes) {
      this.codes = codes;
    }

    public Map<String, Integer> getCounts() {
      return counts;
    }

    public void setCounts(final Map<String, Integer> counts) {
      this.counts = counts;
    }
  }

  private static final Bag BAG = new Bag("b", List.of("x", "y"), Set.of("c"), Map.of("k", 1));

  private static BagEntity held() {
    final var entity = new BagEntity();
    entity.setName("old");
    entity.getTags().add("stale");
    entity.getCodes().add("stale");
    entity.getCounts().put("stale", 9);
    return entity;
  }

  @Nested
  @DisplayName("Merge — into refills the container the target holds")
  class Merge {

    @Test
    @DisplayName("a list, a set and a map the target holds keep their identity and take the source's elements")
    void heldContainersKeepTheirIdentity() {
      final var mapper = Telescope.mapper(Bag.class, BagEntity.class);
      final var entity = held();
      final var tags = entity.getTags();
      final var codes = entity.getCodes();
      final var counts = entity.getCounts();
      mapper.into(entity, BAG);
      assertSame(tags, entity.getTags());
      assertSame(codes, entity.getCodes());
      assertSame(counts, entity.getCounts());
      assertEquals(List.of("x", "y"), entity.getTags());
      assertEquals(Set.of("c"), entity.getCodes());
      assertEquals(Map.of("k", 1), entity.getCounts());
      assertEquals("b", entity.getName());
    }

    @Test
    @DisplayName("a container the target holds none for is built new and written through the setter")
    void aNullHeldContainerIsSetThroughTheSetter() {
      final var mapper = Telescope.mapper(Bag.class, BagEntity.class);
      final var entity = held();
      entity.setTags(null);
      entity.setCounts(null);
      mapper.into(entity, BAG);
      assertEquals(List.of("x", "y"), entity.getTags());
      assertEquals(Map.of("k", 1), entity.getCounts());
    }

    @Test
    @DisplayName("a null source container is written through the setter, as forward gives it")
    void aNullSourceContainerIsWrittenAsNull() {
      final var mapper = Telescope.mapper(Bag.class, BagEntity.class);
      final var entity = held();
      mapper.into(entity, new Bag("b", null, Set.of(), Map.of()));
      assertNull(entity.getTags());
    }

    @Test
    @DisplayName("a container the target holds that cannot be changed is replaced through the setter")
    void anUnchangeableHeldContainerIsReplaced() {
      final var mapper = Telescope.mapper(Bag.class, BagEntity.class);
      final var entity = held();
      entity.setCounts(Collections.unmodifiableMap(new HashMap<String, Integer>()));
      entity.setTags(Arrays.asList("stale"));
      mapper.into(entity, BAG);
      assertEquals(Map.of("k", 1), entity.getCounts());
      assertEquals(List.of("x", "y"), entity.getTags());
      assertEquals("b", entity.getName());
    }

    @Test
    @DisplayName("a second into into what forward built from an unmodifiable source list succeeds")
    void aSecondIntoReplacesTheSharedUnmodifiableList() {
      final var mapper = Telescope.mapper(Bag.class, BagEntity.class);
      final var entity = mapper.forward(BAG);
      mapper.into(entity, new Bag("c", List.of("z"), Set.of(), Map.of()));
      assertEquals(List.of("z"), entity.getTags());
      assertEquals("c", entity.getName());
    }

    @Test
    @DisplayName("a source that hands the target's own collection across leaves it as it is")
    void theSameContainerOnBothSidesIsLeftAlone() {
      final var shared = new ArrayList<>(List.of("x"));
      assertTrue(
        ContainerCopy.refill("into", "S", "T", new Object(), "tags", shared, shared, true, null, null, null, null)
      );
      assertEquals(List.of("x"), shared);
    }
  }

  public record Line(String sku) {}

  public record Order(String id, List<Line> lines) {}

  public static class LineEntity {

    private String sku;
    private OrderEntity order;

    public LineEntity() {}

    public String getSku() {
      return sku;
    }

    public void setSku(final String sku) {
      this.sku = sku;
    }

    public OrderEntity getOrder() {
      return order;
    }

    public void setOrder(final OrderEntity order) {
      this.order = order;
    }
  }

  /**
   * An entity whose collection has a getter and an adder that wires the back-reference, and no
   * setter.
   */
  public static class OrderEntity {

    private String id;
    private final List<LineEntity> lines = new ArrayList<>();

    public OrderEntity() {}

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public List<LineEntity> getLines() {
      return lines;
    }

    public void addLine(final LineEntity line) {
      line.setOrder(this);
      lines.add(line);
    }
  }

  /** As {@link OrderEntity}, with a setter for its collection and no adder. */
  public static class AdderlessEntity {

    private String id;
    private List<LineEntity> lines = new ArrayList<>();

    public AdderlessEntity() {}

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public List<LineEntity> getLines() {
      return lines;
    }

    public void setLines(final List<LineEntity> lines) {
      this.lines = lines;
    }
  }

  private static final Order ORDER = new Order("o", List.of(new Line("a"), new Line("b")));

  @Nested
  @DisplayName("Adders — a collection goes through the owner's adder wherever it has one")
  class Adders {

    @Test
    @DisplayName("forward fills the collection through the adder, with no row naming it, and each line points back")
    void forwardFillsThroughTheAdder() {
      final var mapper = Telescope.mapper(Order.class, OrderEntity.class);
      final var entity = mapper.forward(ORDER);
      assertEquals(List.of("a", "b"), entity.getLines().stream().map(LineEntity::getSku).toList());
      for (final var line : entity.getLines()) assertSame(entity, line.getOrder());
    }

    @Test
    @DisplayName("into clears the collection the target holds and fills it through the adder")
    void intoFillsTheHeldCollectionThroughTheAdder() {
      final var mapper = Telescope.mapper(Order.class, OrderEntity.class);
      final var entity = new OrderEntity();
      final var stale = new LineEntity();
      stale.setSku("stale");
      entity.addLine(stale);
      final var lines = entity.getLines();
      mapper.into(entity, ORDER);
      assertSame(lines, entity.getLines());
      assertEquals(List.of("a", "b"), entity.getLines().stream().map(LineEntity::getSku).toList());
      for (final var line : entity.getLines()) assertSame(entity, line.getOrder());
      assertEquals("o", entity.getId());
    }

    @Test
    @DisplayName("a writeBeans default of setters fills the target's collection through the adder the same way")
    void aSettersDefaultFillsThroughTheAdder() {
      final var mapper = Telescope.mapper(Order.class, OrderEntity.class, writeBeans(SETTERS));
      final var entity = mapper.forward(ORDER);
      for (final var line : entity.getLines()) assertSame(entity, line.getOrder());
    }

    @Test
    @DisplayName("a target whose collection has no adder is written through its setter, with nothing to refuse")
    void aTargetWithNoAdderIsWrittenThroughItsSetter() {
      final var entity = Telescope.mapper(Order.class, AdderlessEntity.class).forward(ORDER);
      assertEquals(List.of("a", "b"), entity.getLines().stream().map(LineEntity::getSku).toList());
      for (final var line : entity.getLines()) assertNull(line.getOrder());
    }

    @Test
    @DisplayName("explain says the collection goes through the adder")
    void explainNamesTheAdder() {
      assertEquals(
        List.of("OrderEntity.lines: through OrderEntity.addLine(LineEntity)"),
        Telescope.mapper(Order.class, OrderEntity.class).explain().collections().stream().map(Object::toString).toList()
      );
    }
  }
}
