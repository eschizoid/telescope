package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the instanceof / record-pattern branches in {@link DeepMap} that the Java 17 cross-compile
 * (PR #80) rewrote — specifically the post-fixup row routing inside {@code applyForward} / {@code
 * applyBackward} (covering {@code Constant}, {@code Compute}, {@code TelescopeToTelescope} zip /
 * broadcast, and {@code FromTelescopeTo}), and the {@code DefaultWriteHint} record-pattern site in
 * {@code extractDefaultStrategy}.
 *
 * <p>The base {@code TelescopeTest} / {@code MappingTest} fixtures cover the common rows; this file
 * targets the specific branches whose codecov coverage degraded when the sealed-switch
 * exhaustiveness check moved from compile-time (pre-#80) to runtime-fallthrough (post-#80).
 */
class DeepMapBranchTest {

  record SrcRecord(String id, String email) {}

  record TgtRecord(String id, String email, String tenant, String traceId) {}

  @Nested
  @DisplayName("applyForward — Constant / Compute branches")
  class ConstantCompute {

    @Test
    @DisplayName("constant(tgt, value) lands the value at the target field on forward")
    void constantForwardLandsValue() {
      final var mapper = Telescope.mapper(
        SrcRecord.class,
        TgtRecord.class,
        to(SrcRecord::id, TgtRecord::id),
        to(SrcRecord::email, TgtRecord::email),
        constant(TgtRecord::tenant, "production"),
        constant(TgtRecord::traceId, "fixed-trace")
      );

      assertEquals(
        new TgtRecord("e-1", "a@b", "production", "fixed-trace"),
        mapper.forward(new SrcRecord("e-1", "a@b"))
      );
    }

    @Test
    @DisplayName("compute(tgt, supplier) runs the supplier on forward")
    void computeForwardRunsSupplier() {
      final var mapper = Telescope.mapper(
        SrcRecord.class,
        TgtRecord.class,
        to(SrcRecord::id, TgtRecord::id),
        to(SrcRecord::email, TgtRecord::email),
        constant(TgtRecord::tenant, "x"),
        compute(TgtRecord::traceId, () -> "generated-" + 42)
      );

      assertEquals("generated-42", mapper.forward(new SrcRecord("e-1", "a@b")).traceId());
    }
  }

  @Nested
  @DisplayName("applyForward / applyBackward — TelescopeToTelescope ZIP")
  class ZipBranches {

    record CartLine(String sku, int qty) {}

    record DtoLine(String code, int count) {}

    record Cart(List<CartLine> lines) {}

    record CartDto(List<DtoLine> rows) {}

    @Test
    @DisplayName("zip cardinality mismatch on forward throws with a self-diagnosing message")
    void zipCardinalityMismatch() {
      final var mapper = Telescope.mapper(
        Cart.class,
        CartDto.class,
        zip(
          Telescope.of(Cart.class).each(Cart::lines).field(CartLine::sku),
          Telescope.of(CartDto.class).each(CartDto::rows).field(DtoLine::code)
        ),
        zip(
          Telescope.of(Cart.class).each(Cart::lines).field(CartLine::qty),
          Telescope.of(CartDto.class).each(CartDto::rows).field(DtoLine::count)
        )
      );

      final var src = new Cart(List.of(new CartLine("a", 1), new CartLine("b", 2)));
      final var seed = new CartDto(List.of(new DtoLine("x", 0), new DtoLine("y", 0), new DtoLine("z", 0)));

      // forward writes positionally into the seed; mismatching cardinality should throw.
      final var ex = assertThrows(IllegalStateException.class, () -> mapper.asTelescope().set(src, seed));
      assertTrue(
        ex.getMessage().toLowerCase().contains("cardinality"),
        () -> "expected message to mention cardinality, was: " + ex.getMessage()
      );
    }
  }

  @Nested
  @DisplayName("extractDefaultStrategy — DefaultWriteHint record-pattern site")
  class DefaultStrategy {

    public static final class TgtBean {

      private String id;
      private String email;
      private String tenant;
      private String traceId;

      public String getId() {
        return id;
      }

      public void setId(final String id) {
        this.id = id;
      }

      public String getEmail() {
        return email;
      }

      public void setEmail(final String email) {
        this.email = email;
      }

      public String getTenant() {
        return tenant;
      }

      public void setTenant(final String tenant) {
        this.tenant = tenant;
      }

      public String getTraceId() {
        return traceId;
      }

      public void setTraceId(final String traceId) {
        this.traceId = traceId;
      }
    }

    @Test
    @DisplayName("writeBeans(SETTERS) default routes through extractDefaultStrategy → applies to unhinted targets")
    void defaultWriteHintApplies() {
      final var mapper = Telescope.mapper(
        SrcRecord.class,
        TgtBean.class,
        to(SrcRecord::id, TgtBean::getId),
        to(SrcRecord::email, TgtBean::getEmail),
        constant(TgtBean::getTenant, "production"),
        constant(TgtBean::getTraceId, "fixed-trace"),
        writeBeans(SETTERS)
      );

      final var out = mapper.forward(new SrcRecord("e-1", "a@b"));
      assertEquals("e-1", out.getId());
      assertEquals("a@b", out.getEmail());
      assertEquals("production", out.getTenant());
      assertEquals("fixed-trace", out.getTraceId());
    }

    @Test
    @DisplayName("duplicate writeBeans(...) default throws at resolve time")
    void duplicateDefaultThrows() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(
          SrcRecord.class,
          TgtBean.class,
          to(SrcRecord::id, TgtBean::getId),
          to(SrcRecord::email, TgtBean::getEmail),
          constant(TgtBean::getTenant, "x"),
          constant(TgtBean::getTraceId, "y"),
          writeBeans(SETTERS),
          writeBeans(SETTERS)
        )
      );
      assertTrue(ex.getMessage().contains("Duplicate"));
    }
  }
}
