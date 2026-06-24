package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Mapper.into(target, source)} — the {@code @MappingTarget} equivalent for in-place
 * bean target updates. Common JPA pattern: load entity by ID, mutate with DTO fields, save —
 * preserving the entity's identity so the persistence context's tracking survives.
 */
class MapperIntoTest {

  record OrderDto(String id, String customerName, int quantity) {}

  static class OrderEntity {

    private String id;
    private String customerName;
    private int quantity;

    public OrderEntity() {}

    public OrderEntity(final String id, final String customerName, final int quantity) {
      this.id = id;
      this.customerName = customerName;
      this.quantity = quantity;
    }

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public String getCustomerName() {
      return customerName;
    }

    public void setCustomerName(final String customerName) {
      this.customerName = customerName;
    }

    public int getQuantity() {
      return quantity;
    }

    public void setQuantity(final int quantity) {
      this.quantity = quantity;
    }
  }

  @Nested
  @DisplayName("Two-phase apply — every read completes before any setter runs")
  class StagedWriteAtomicity {

    record SimpleSrc(String first, String second, String third) {}

    static class CountedEntity {

      private String first;
      private String second;
      private String third;
      static final AtomicInteger setterFires = new AtomicInteger();

      public CountedEntity() {}

      public String getFirst() {
        return first;
      }

      public void setFirst(final String first) {
        setterFires.incrementAndGet();
        this.first = first;
      }

      public String getSecond() {
        return second;
      }

      public void setSecond(final String second) {
        setterFires.incrementAndGet();
        this.second = second;
      }

      public String getThird() {
        return third;
      }

      public void setThird(final String third) {
        setterFires.incrementAndGet();
        this.third = third;
      }
    }

    @Test
    @DisplayName("6 setter fires: 3 from forward() + 3 from into() — every patch-table slot reaches its setter")
    void stagedWritePattern() {
      final var mapper = Telescope.mapper(SimpleSrc.class, CountedEntity.class);
      final var entity = new CountedEntity();
      CountedEntity.setterFires.set(0);

      mapper.into(entity, new SimpleSrc("a", "b", "c"));

      assertEquals(
        6,
        CountedEntity.setterFires.get(),
        "3 from forward() + 3 from into() — every slot reaches its setter"
      );
      assertEquals("a", entity.getFirst());
      assertEquals("b", entity.getSecond());
      assertEquals("c", entity.getThird());
    }
  }

  @Nested
  @DisplayName("Bean target — in-place mutation preserves target identity")
  class BeanTarget {

    @Test
    @DisplayName("into(managed, dto) mutates target via setters; reference identity preserved")
    void preservesIdentity() {
      final var mapper = Telescope.mapper(OrderDto.class, OrderEntity.class);
      final var managed = new OrderEntity("seed-id", "OLD-NAME", 0);

      final var result = mapper.into(managed, new OrderDto("ORD-1", "Alice", 42));

      assertSame(managed, result, "into(...) returns the same reference passed in");
      assertEquals("ORD-1", managed.getId(), "id setter fired");
      assertEquals("Alice", managed.getCustomerName(), "customerName setter fired");
      assertEquals(42, managed.getQuantity(), "quantity setter fired");
    }

    @Test
    @DisplayName("Subsequent into(...) calls re-mutate the same instance")
    void repeatableMutation() {
      final var mapper = Telescope.mapper(OrderDto.class, OrderEntity.class);
      final var managed = new OrderEntity();

      mapper.into(managed, new OrderDto("a", "first", 1));
      assertEquals("a", managed.getId());

      mapper.into(managed, new OrderDto("b", "second", 2));
      assertEquals("b", managed.getId());
      assertEquals("second", managed.getCustomerName());
      assertEquals(2, managed.getQuantity());
    }
  }

  @Nested
  @DisplayName("Hook chain composition — before/after hooks run on into() the same as forward()")
  class HookChainComposition {

    @Test
    @DisplayName("afterForward hook runs on into() — fields stamped post-mapping land on the existing target")
    void afterForwardFires() {
      final var mapper = Telescope.mapper(OrderDto.class, OrderEntity.class).afterForward(e -> {
        e.setCustomerName(e.getCustomerName() + "-STAMPED");
        return e;
      });

      final var managed = new OrderEntity();
      mapper.into(managed, new OrderDto("ORD-1", "Alice", 1));

      assertEquals("Alice-STAMPED", managed.getCustomerName(), "afterForward hook fired on into() path");
    }

    @Test
    @DisplayName("beforeForward hook normalizes source before into() applies")
    void beforeForwardFires() {
      final var mapper = Telescope.mapper(OrderDto.class, OrderEntity.class).beforeForward(dto ->
        new OrderDto(dto.id().toUpperCase(), dto.customerName(), dto.quantity())
      );

      final var managed = new OrderEntity();
      mapper.into(managed, new OrderDto("ord-1", "Alice", 1));

      assertEquals("ORD-1", managed.getId(), "beforeForward hook normalized the id");
    }
  }

  @Nested
  @DisplayName("Record target — into() throws UnsupportedOperationException")
  class RecordTargetRejected {

    record DtoRec(String name) {}

    record EntityRec(String name) {}

    @Test
    @DisplayName("calling into() with a record target throws with a clear message pointing at forward()")
    void recordTargetRejected() {
      final var mapper = Telescope.mapper(DtoRec.class, EntityRec.class);
      final var existing = new EntityRec("old");

      final var ex = assertThrows(UnsupportedOperationException.class, () -> mapper.into(existing, new DtoRec("new")));

      assertTrue(ex.getMessage().contains("records are immutable"), "message explains the constraint");
      assertTrue(ex.getMessage().contains("Mapper.forward"), "message suggests the alternative");
    }
  }

  @Nested
  @DisplayName("Null guards — into() rejects null target or source up front")
  class NullGuards {

    @Test
    @DisplayName("null target → NullPointerException")
    void nullTarget() {
      final var mapper = Telescope.mapper(OrderDto.class, OrderEntity.class);
      assertThrows(NullPointerException.class, () -> mapper.into(null, new OrderDto("a", "b", 1)));
    }

    @Test
    @DisplayName("null source → NullPointerException")
    void nullSource() {
      final var mapper = Telescope.mapper(OrderDto.class, OrderEntity.class);
      assertThrows(NullPointerException.class, () -> mapper.into(new OrderEntity(), null));
    }
  }

  @Nested
  @DisplayName("Missing setter — silently skipped to match SettersWriter / MapStruct @MappingTarget")
  class MissingSetter {

    record DtoOnly(String id, String missingOnEntity) {}

    static class EntityOnlyId {

      private String id;

      public EntityOnlyId() {}

      public String getId() {
        return id;
      }

      public void setId(final String id) {
        this.id = id;
      }

      public String getMissingOnEntity() {
        return null;
      }
      // no setMissingOnEntity intentionally
    }

    @Test
    @DisplayName("property without a setter is silently skipped — matches SettersWriter")
    void missingSetterIsSilentlySkipped() {
      // Mapper.into and Mapper.forward both reach the target through Beans setter dispatch;
      // both paths must silently skip an absent setter to keep the contract symmetric (matches
      // MapStruct @MappingTarget behaviour). The setId() target is still written; the
      // missingOnEntity target stays at its JLS default.
      final var mapper = Telescope.mapper(DtoOnly.class, EntityOnlyId.class);
      final var target = new EntityOnlyId();
      assertDoesNotThrow(() -> mapper.into(target, new DtoOnly("a", "b")));
      assertEquals("a", target.getId());
    }
  }

  @Nested
  @DisplayName("Bean → bean — both sides POJO; into() works in both forward direction senses")
  class BeanToBean {

    static class Src {

      private String name;
      private int age;

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public int getAge() {
        return age;
      }

      public void setAge(final int age) {
        this.age = age;
      }

      public Src() {}

      public Src(final String name, final int age) {
        this.name = name;
        this.age = age;
      }
    }

    static class Dst {

      private String name;
      private int age;

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public int getAge() {
        return age;
      }

      public void setAge(final int age) {
        this.age = age;
      }

      public Dst() {}
    }

    @Test
    @DisplayName("into(existingDst, src) — values flow into existing destination, identity preserved")
    void srcToExistingDst() {
      final var mapper = Telescope.mapper(Src.class, Dst.class);
      final var dst = new Dst();
      dst.setName("STAYS-IF-NOT-OVERWRITTEN");

      mapper.into(dst, new Src("Alice", 30));

      assertEquals("Alice", dst.getName());
      assertEquals(30, dst.getAge());
    }
  }

  @Nested
  @DisplayName("Patch composition — into() reuses forward() so patch-style hooks work")
  class PatchInteraction {

    @Test
    @DisplayName("a mapper configured with hooks applies them through into() identically to forward()")
    void hookSemantics() {
      final var mapper = Telescope.mapper(OrderDto.class, OrderEntity.class).afterForward(e -> {
        e.setQuantity(e.getQuantity() * 2);
        return e;
      });

      final var managed = new OrderEntity();
      mapper.into(managed, new OrderDto("a", "Alice", 5));

      assertEquals(10, managed.getQuantity(), "hook doubled quantity post-mapping");
      // Same hook fires via forward() — verify symmetry
      final var fresh = mapper.forward(new OrderDto("a", "Alice", 5));
      assertNotNull(fresh, "forward() also produces a valid result");
      assertEquals(10, fresh.getQuantity(), "fresh forward() result identically transformed");
    }
  }
}
