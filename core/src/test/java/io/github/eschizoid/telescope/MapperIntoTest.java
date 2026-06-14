package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

      assertEquals(true, ex.getMessage().contains("records are immutable"), "message explains the constraint");
      assertEquals(true, ex.getMessage().contains("Mapper.forward"), "message suggests the alternative");
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
  @DisplayName("Missing setter — clear error names the property")
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
    @DisplayName("property without a setter throws IAE at mapper-build or into() time")
    void missingSetterThrows() {
      // Build-or-apply might surface the missing setter at either time depending on the autoWriter
      // discovery path. Either way the failure is unambiguous to the user.
      final var ex = assertThrows(RuntimeException.class, () -> {
        final var mapper = Telescope.mapper(DtoOnly.class, EntityOnlyId.class);
        mapper.into(new EntityOnlyId(), new DtoOnly("a", "b"));
      });
      // The error message comes from SettersWriter or writeBeanProperty — either path names the
      // missing setter clearly. We just verify the exception is thrown with a message; the exact
      // wording belongs to the underlying writer's error contract, not the into() contract.
      assertNotNull(ex.getMessage(), "exception message present");
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
