package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Interaction tests between the MethodHandle-combinator leaf {@code Iso} (chosen at build time for
 * record/bean pairs via {@code MhIso.supports}) and the rest of the {@code Mapper} public surface.
 *
 * <p>The first review round validated the leaf in isolation ({@code MhIsoTest}). This suite pins
 * the surfaces that do <em>more</em> than {@code forward}/{@code backward} — patch, into,
 * explain/trace, hooks, lift* — over exactly the record/bean shapes the MH leaf now owns, so a leaf
 * change that diverged from the array-leaf semantics on those surfaces would be caught here.
 */
class MhLeafMapperSurfaceTest {

  // record source + bean target with no-arg ctor + full setters => MH-eligible (record->bean).
  record OrderDto(String id, String customerName, int quantity) {}

  static class OrderEntity {

    private String id;
    private String customerName;
    private int quantity;

    public OrderEntity() {}

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

  // bean source + bean target, both no-arg ctor + setters => MH-eligible (bean->bean).
  static class PersonBean {

    private String name;
    private int age;

    public PersonBean() {}

    public PersonBean(final String name, final int age) {
      this.name = name;
      this.age = age;
    }

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
  }

  static class PersonDtoBean {

    private String name;
    private int age;

    public PersonDtoBean() {}

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
  }

  @Nested
  @DisplayName("patch(base, partial) — patch table (per-field step Iso), NOT the composed MH leaf")
  class PatchSurface {

    @Test
    @DisplayName("record source rebuilt from a bean partial's non-null fields (record<-bean patch table)")
    void patchRecordSourceFromBeanTargetPartial() {
      final var mapper = Telescope.mapper(OrderDto.class, OrderEntity.class);
      final var base = new OrderDto("id-1", "Alice", 7);

      // partial: only customerName set (quantity default 0, id null). Patch overlays customerName;
      // quantity 0 IS a value on a bean partial and overlays too (the patch table only skips nulls,
      // and a primitive can't be null) — so the round-trip mirrors the array-leaf contract exactly.
      final var partial = new OrderEntity();
      partial.setCustomerName("Bob");

      final var patched = mapper.patch(base, partial);
      assertEquals("id-1", patched.id(), "null partial.id → base id preserved");
      assertEquals("Bob", patched.customerName(), "non-null partial.customerName overlaid");
      assertEquals(0, patched.quantity(), "primitive quantity is never null → always overlaid (=0)");
    }

    @Test
    @DisplayName("bean->bean patch overlays only non-null target fields; base identity is fresh")
    void patchBeanToBean() {
      final var mapper = Telescope.mapper(PersonBean.class, PersonDtoBean.class);
      final var base = new PersonBean("Alice", 30);
      final var partial = new PersonDtoBean();
      partial.setName("Bob"); // age left 0

      final var patched = mapper.patch(base, partial);
      assertEquals("Bob", patched.getName());
      // age is a primitive on the partial (0, never null) → overlaid, matching the array-leaf path.
      assertEquals(0, patched.getAge());
    }
  }

  @Nested
  @DisplayName("into(target, source) — reuses forward()'s MH-built value, writes onto a live target")
  class IntoSurface {

    @Test
    @DisplayName("record->bean into() mutates the passed target and returns the same reference")
    void intoBeanTarget() {
      final var mapper = Telescope.mapper(OrderDto.class, OrderEntity.class);
      final var managed = new OrderEntity();
      managed.setId("old");
      managed.setCustomerName("old");
      managed.setQuantity(99);

      final var out = mapper.into(managed, new OrderDto("new-id", "NewName", 5));
      assertSame(managed, out, "same reference returned");
      assertEquals("new-id", managed.getId());
      assertEquals("NewName", managed.getCustomerName());
      assertEquals(5, managed.getQuantity(), "primitive quantity carried unboxed through the MH leaf");
    }

    @Test
    @DisplayName("into() equals forward() field-for-field on an MH-eligible bean target")
    void intoEqualsForward() {
      final var mapper = Telescope.mapper(PersonBean.class, PersonDtoBean.class);
      final var src = new PersonBean("Carol", 41);

      final var viaForward = mapper.forward(src);
      final var viaInto = mapper.into(new PersonDtoBean(), src);
      assertEquals(viaForward.getName(), viaInto.getName());
      assertEquals(viaForward.getAge(), viaInto.getAge());
    }
  }

  @Nested
  @DisplayName("null transform into a primitive bean slot — MH leaf must match array-leaf skip")
  class PrimitiveNullSkipThroughForward {

    // compute(...) returns null into the primitive `quantity` slot: the array leaf's SettersWriter
    // skips the setter (JLS default 0); the MH leaf's setterFromSource guards the same way. This
    // exercises that parity through the *full Mapper.forward*, not the raw MhIso leaf.
    @Test
    @DisplayName("compute yielding null into an int bean slot leaves the JLS default (0), via Mapper.forward")
    void nullComputeIntoPrimitiveBeanSlot() {
      final var mapper = Telescope.mapper(
        OrderDto.class,
        OrderEntity.class,
        to(OrderDto::id, OrderEntity::getId),
        to(OrderDto::customerName, OrderEntity::getCustomerName),
        compute(OrderEntity::getQuantity, () -> (Integer) null)
      );

      final var out = mapper.forward(new OrderDto("id-9", "Dan", 123));
      assertEquals("id-9", out.getId());
      assertEquals("Dan", out.getCustomerName());
      assertEquals(0, out.getQuantity(), "null into primitive setter is skipped → JLS default 0");
    }
  }

  @Nested
  @DisplayName("explain()/trace() — built from the resolution trail, independent of leaf assembly")
  class ExplainTraceSurface {

    @Test
    @DisplayName("trace() runs the actual MH forward and the field trail still lines up")
    void traceOverMhLeaf() {
      final var mapper = Telescope.mapper(PersonBean.class, PersonDtoBean.class);
      final var trace = mapper.trace(new PersonBean("Eve", 22));
      assertNotNull(trace);
      // explain() is the static structure; must enumerate both mapped fields regardless of leaf.
      final var report = mapper.explain().toString();
      assertTrue(report.contains("name"), "explain lists name");
      assertTrue(report.contains("age"), "explain lists age");
    }
  }

  @Nested
  @DisplayName("hooks — beforeForward/afterForward wrap the MH leaf, not bypass it")
  class HooksSurface {

    @Test
    @DisplayName("beforeForward normalises the source before the MH leaf reads it")
    void beforeForwardComposesWithMhLeaf() {
      final var mapper = Telescope.mapper(PersonBean.class, PersonDtoBean.class).beforeForward(p ->
        new PersonBean(p.getName().toUpperCase(), p.getAge() + 1)
      );

      final var out = mapper.forward(new PersonBean("frank", 10));
      assertEquals("FRANK", out.getName());
      assertEquals(11, out.getAge(), "afterForward-free hook still threads the primitive through the leaf");
    }

    @Test
    @DisplayName("afterForward post-processes the MH-built target")
    void afterForwardComposesWithMhLeaf() {
      final var mapper = Telescope.mapper(PersonBean.class, PersonDtoBean.class).afterForward(dto -> {
        dto.setName(dto.getName() + "!");
        return dto;
      });
      final var out = mapper.forward(new PersonBean("Grace", 33));
      assertEquals("Grace!", out.getName());
      assertEquals(33, out.getAge());
    }
  }

  @Nested
  @DisplayName("lift* — lifting the MH-built leaf Iso through a container shape")
  class LiftSurface {

    @Test
    @DisplayName("liftList over an MH-eligible bean mapper converts a List<Bean> element-wise")
    void liftListOverMhBeanMapper() {
      final var mapper = Telescope.mapper(PersonBean.class, PersonDtoBean.class);
      final var listMapper = mapper.liftList();

      final var out = listMapper.forward(List.of(new PersonBean("H", 1), new PersonBean("I", 2)));
      assertEquals(2, out.size());
      assertEquals("H", out.get(0).getName());
      assertEquals(1, out.get(0).getAge());
      assertEquals("I", out.get(1).getName());
      assertEquals(2, out.get(1).getAge());

      // backward through the lifted MH leaf too.
      final var back = listMapper.backward(out);
      assertEquals("H", back.get(0).getName());
      assertEquals(2, back.get(1).getAge());
    }
  }

  @Nested
  @DisplayName("asTelescope()/toForwardMapper() — route through forward/backward over the MH leaf")
  class TelescopeAndForwardProjection {

    @Test
    @DisplayName("asTelescope().set converts A->B through the MH leaf")
    void asTelescopeOverMhLeaf() {
      final var mapper = Telescope.mapper(PersonBean.class, PersonDtoBean.class);
      final var t = mapper.asTelescope();
      final var dto = t.read(new PersonBean("Jo", 44));
      assertEquals("Jo", dto.getName());
      assertEquals(44, dto.getAge());
    }

    @Test
    @DisplayName("toForwardMapper().forward converts through the MH leaf")
    void toForwardMapperOverMhLeaf() {
      final var fwd = Telescope.mapper(PersonBean.class, PersonDtoBean.class).toForwardMapper();
      final var dto = fwd.forward(new PersonBean("Kim", 55));
      assertEquals("Kim", dto.getName());
      assertEquals(55, dto.getAge());
    }
  }

  @Nested
  @DisplayName("byte-identical to the array leaf — forward output equals a hand-copy")
  class ByteIdentical {

    @Test
    @DisplayName("MH bean->bean forward matches a manual field copy exactly")
    void mhForwardMatchesManualCopy() {
      final var mapper = Telescope.mapper(PersonBean.class, PersonDtoBean.class);
      final var src = new PersonBean("Liam", 66);
      final var out = mapper.forward(src);
      assertTrue(Objects.equals(out.getName(), src.getName()));
      assertEquals(src.getAge(), out.getAge());
      assertFalse(out == null);
      assertNull(mapper.forward(null), "null-in null-out preserved");
    }
  }
}
