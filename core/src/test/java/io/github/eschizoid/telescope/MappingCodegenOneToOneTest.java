package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Codegen-1:1 verification across all three target-side mapping features: intermediate allocation,
 * {@link io.github.eschizoid.telescope.mapping.Mapping#constant Mapping.constant}, and {@link
 * io.github.eschizoid.telescope.mapping.Mapping#compute Mapping.compute}. Each feature is exercised
 * in three shapes:
 *
 * <ol>
 *   <li><b>Flat accessor</b> — bare {@code Tgt::field} method reference.
 *   <li><b>Runtime telescope</b> — {@code Telescope.of(Tgt.class).field(...).field(...)} chain.
 *   <li><b>Codegen navigator</b> — {@code TgtTelescope.of().field()...} chain emitted by the
 *       {@code @Focus} processor.
 * </ol>
 *
 * <p>The shapes must produce identical behavior at the {@code Telescope<S, A>} value level — the
 * codegen-emitted navigator routes through the same {@code Telescope.lens(Accessor, BiFunction)}
 * overload as the runtime path, so {@code firstHopName} recovery and the intermediate-allocation
 * engine's behavior are guaranteed to match. These tests pin the guarantee end-to-end against the
 * actual on-disk generated navigator classes ({@link PersonTelescope}, {@link AddressTelescope}).
 *
 * <p>The two top-level {@code @Focus} fixtures used here, {@link Person} and {@link Address}, are
 * sibling files in this test package so the codegen processor emits real navigator classes at
 * test-compile time.
 */
class MappingCodegenOneToOneTest {

  // Genuinely-flat source — no `address` slot for the intermediate-allocation case.
  record Slim(String value) {}

  @Nested
  @DisplayName("intermediate allocation — same Address synthesized for flat / runtime / codegen")
  class IntermediateAllocation {

    @Test
    @DisplayName("runtime Telescope.of(...).field(...).field(...) — intermediate Address allocated")
    void runtimeTelescope() {
      final var mapper = Telescope.mapper(
        Slim.class,
        Person.class,
        to(Slim::value, Telescope.of(Person.class).field(Person::address).field(Address::city))
      );

      final var out = mapper.forward(new Slim("Brooklyn"));
      assertNotNull(out.address(), "intermediate Address allocated by the engine");
      assertEquals("Brooklyn", out.address().city());
    }

    @Test
    @DisplayName("codegen PersonTelescope.of().address().city() — same allocator path")
    void codegenNavigator() {
      final var mapper = Telescope.mapper(
        Slim.class,
        Person.class,
        to(Slim::value, PersonTelescope.of().address().city())
      );

      final var out = mapper.forward(new Slim("Brooklyn"));
      assertNotNull(out.address(), "intermediate Address allocated by the engine via codegen navigator");
      assertEquals("Brooklyn", out.address().city());
    }
  }

  @Nested
  @DisplayName("Mapping.constant — same literal stamped via flat / runtime / codegen targets")
  class ConstantInjection {

    @Test
    @DisplayName("flat Tgt::field — single-hop accessor, wrapped internally into a one-hop telescope")
    void flatAccessor() {
      // Top-level field on the Address record — flat factory wraps Address::zip into a single-hop
      // telescope at construction time. Source is empty (Address has no Address sub-field), so we
      // route the value through a wrapper Src for the engine to recurse over.
      record SrcZ(String city) {}

      final var mapper = Telescope.mapper(
        SrcZ.class,
        Address.class,
        to(SrcZ::city, Address::city),
        constant(Address::zip, "11201")
      );

      final var out = mapper.forward(new SrcZ("Brooklyn"));
      assertEquals("Brooklyn", out.city());
      assertEquals("11201", out.zip()); // constant stamped
    }

    @Test
    @DisplayName("runtime Telescope.of(Person.class).field(...).field(...) — nested target via runtime chain")
    void runtimeTelescope() {
      record SrcZ(String name) {}

      final var mapper = Telescope.mapper(
        SrcZ.class,
        Person.class,
        to(SrcZ::name, Person::name),
        constant(Telescope.of(Person.class).field(Person::address).field(Address::zip), "11201")
      );

      final var out = mapper.forward(new SrcZ("Alice"));
      assertEquals("Alice", out.name());
      assertNotNull(out.address(), "intermediate allocated because the constant claims address as a write hop");
      assertEquals("11201", out.address().zip()); // constant stamped at nested target
    }

    @Test
    @DisplayName("codegen PersonTelescope.of().address().zip() — same constant behavior end-to-end")
    void codegenNavigator() {
      record SrcZ(String name) {}

      final var mapper = Telescope.mapper(
        SrcZ.class,
        Person.class,
        to(SrcZ::name, Person::name),
        constant(PersonTelescope.of().address().zip(), "11201")
      );

      final var out = mapper.forward(new SrcZ("Alice"));
      assertEquals("Alice", out.name());
      assertNotNull(out.address(), "intermediate allocated via codegen navigator firstHopName");
      assertEquals("11201", out.address().zip());
    }
  }

  @Nested
  @DisplayName("Mapping.compute — fresh supplier value stamped via flat / runtime / codegen targets")
  class ComputeInjection {

    @Test
    @DisplayName("flat Tgt::field — supplier wrapped internally, fresh value per call")
    void flatAccessor() {
      record SrcZ(String city) {}

      final var counter = new int[] { 0 };
      final var mapper = Telescope.mapper(
        SrcZ.class,
        Address.class,
        to(SrcZ::city, Address::city),
        compute(Address::zip, () -> String.format("%05d", ++counter[0]))
      );

      final var t1 = mapper.forward(new SrcZ("Brooklyn"));
      final var t2 = mapper.forward(new SrcZ("Queens"));
      assertEquals("00001", t1.zip());
      assertEquals("00002", t2.zip()); // fresh per call
    }

    @Test
    @DisplayName("runtime Telescope.of(Person.class).field(...).field(...) — supplier at nested location")
    void runtimeTelescope() {
      record SrcZ(String name) {}

      final var counter = new int[] { 0 };
      final var mapper = Telescope.mapper(
        SrcZ.class,
        Person.class,
        to(SrcZ::name, Person::name),
        compute(Telescope.of(Person.class).field(Person::address).field(Address::zip), () ->
          String.format("%05d", ++counter[0])
        )
      );

      final var t1 = mapper.forward(new SrcZ("Alice"));
      final var t2 = mapper.forward(new SrcZ("Bob"));
      assertEquals("00001", t1.address().zip());
      assertEquals("00002", t2.address().zip());
    }

    @Test
    @DisplayName("codegen PersonTelescope.of().address().zip() — supplier identical behavior")
    void codegenNavigator() {
      record SrcZ(String name) {}

      final var counter = new int[] { 0 };
      final var mapper = Telescope.mapper(
        SrcZ.class,
        Person.class,
        to(SrcZ::name, Person::name),
        compute(PersonTelescope.of().address().zip(), () -> String.format("%05d", ++counter[0]))
      );

      final var t1 = mapper.forward(new SrcZ("Alice"));
      final var t2 = mapper.forward(new SrcZ("Bob"));
      assertEquals("00001", t1.address().zip());
      assertEquals("00002", t2.address().zip());
    }
  }

  @Nested
  @DisplayName("all three features composed in one mapper — flat src into deeply-nested codegen target")
  class ComposedEndToEnd {

    @Test
    @DisplayName("a single mapper combines intermediate allocation + constant + compute via codegen navigators")
    void allThreeFeaturesAtOnce() {
      record FlatSrc(String displayName, String displayCity) {}

      // age has no Src counterpart → drop. address is allocated by intermediate-allocation. city
      // routes from displayCity via the codegen navigator. zip is a constant. name is computed.
      final var mapper = Telescope.mapper(
        FlatSrc.class,
        Person.class,
        drop(FlatSrc::displayName),
        drop(FlatSrc::displayCity),
        compute(PersonTelescope.of().name(), () -> "computed-name"),
        to(FlatSrc::displayCity, PersonTelescope.of().address().city()),
        constant(PersonTelescope.of().address().zip(), "11201")
      );

      final var out = mapper.forward(new FlatSrc("ignored", "Brooklyn"));
      assertEquals("computed-name", out.name()); // compute via codegen navigator
      assertEquals(0, out.age()); // primitive default, no source counterpart
      assertNotNull(out.address(), "intermediate Address allocated");
      assertEquals("Brooklyn", out.address().city()); // routed via codegen navigator
      assertEquals("11201", out.address().zip()); // constant via codegen navigator
    }
  }
}
