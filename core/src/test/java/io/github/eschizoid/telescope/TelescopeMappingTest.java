package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.mapping.Mapping;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the four telescope-aware {@code Mapping} factories — the public analogue of
 * MapStruct's {@code @Mapping(source = "...", target = "...")} for nested-path correspondences.
 *
 * <ul>
 *   <li>{@code to(Accessor, Telescope)} — flat source → nested target
 *   <li>{@code to(Telescope, Accessor)} — nested source → flat target
 *   <li>{@code to(Telescope, Telescope)} — both nested (broadcast when many-focus)
 *   <li>{@code zip(Telescope, Telescope)} — positional N:N, cardinality-checked
 * </ul>
 *
 * <p>All telescope rows are post-fixup overlays on top of the auto-recursion result; the fixtures
 * here use same-named fields so auto-mapping handles the base shape and the telescope row stamps
 * additional values at nested locations.
 */
class TelescopeMappingTest {

  // ---------- Single-focus fixtures (same-named, auto-mappable) ----------
  record Inner(String code) {}

  record A(String label, Inner inner) {}

  record B(String label, Inner inner) {}

  @Nested
  @DisplayName("Mapping.to(Accessor, Telescope) — flat source → nested target")
  class FlatSrcToNestedTgt {

    @Test
    @DisplayName("forward overlays the source value at the target's nested leaf")
    void forwardOverlaysLeaf() {
      final var mapper = Telescope.mapper(
        A.class,
        B.class,
        to(A::label, Telescope.of(B.class).field(B::inner).field(Inner::code))
      );

      final var out = mapper.forward(new A("LABEL-A", new Inner("ignored")));
      assertEquals("LABEL-A", out.label()); // auto-mapped
      assertEquals("LABEL-A", out.inner().code()); // telescope overlay
    }

    @Test
    @DisplayName("backward reads the nested target leaf and rebuilds the source")
    void backwardReadsNestedLeaf() {
      final var mapper = Telescope.mapper(
        A.class,
        B.class,
        to(A::label, Telescope.of(B.class).field(B::inner).field(Inner::code))
      );

      final var back = mapper.backward(new B("OUTER", new Inner("FROM-NESTED")));
      assertEquals("FROM-NESTED", back.label()); // telescope read wins on backward
    }

    @Test
    @DisplayName("multiple to() rows reading the same source field — additive forward writes")
    void multipleRowsSharingSource() {
      record Recipient(String fullName, String greetingName) {}
      record Src(String label, Recipient recipient) {}
      record Tgt(String label, Recipient recipient) {}

      final var mapper = Telescope.mapper(
        Src.class,
        Tgt.class,
        // Auto-recursion handles Src::label → Tgt::label and Src::recipient → Tgt::recipient.
        // Both telescope rows read from Src::label and overlay it at different nested leaves
        // —
        // closest analogue to MapStruct's:
        //   @Mapping(source = "label", target = "recipient.fullName")
        //   @Mapping(source = "label", target = "recipient.greetingName")
        to(Src::label, Telescope.of(Tgt.class).field(Tgt::recipient).field(Recipient::fullName)),
        to(Src::label, Telescope.of(Tgt.class).field(Tgt::recipient).field(Recipient::greetingName))
      );

      final var out = mapper.forward(new Src("VAL", new Recipient("ignored1", "ignored2")));
      assertEquals("VAL", out.label());
      assertEquals("VAL", out.recipient().fullName());
      assertEquals("VAL", out.recipient().greetingName());
    }
  }

  @Nested
  @DisplayName("Codegen 1:1 with runtime — Mapping.to works against @Focus-generated navigators")
  class CodegenOnePointOne {

    @Test
    @DisplayName("a codegen-style hand-built Telescope.lens chain is interchangeable with .field(...)")
    void handBuiltLensChainBehavesLikeFieldChain() {
      // Hand-built like @Focus codegen would emit: Telescope.lens(getter, setter) composed via
      // .then(...). This must behave identically to
      // Telescope.of(B.class).field(B::inner).field(...)
      // when fed into Mapping.to — same routing, same forward / backward result.
      final var codegenStyleTgt = Telescope.<B, Inner>lens(B::inner, (s, v) -> new B(s.label(), v)).then(
        Telescope.<Inner, String>lens(Inner::code, (s, v) -> new Inner(v))
      );

      final var mapper = Telescope.mapper(A.class, B.class, to(A::label, codegenStyleTgt));

      final var out = mapper.forward(new A("LABEL", new Inner("ignored")));
      assertEquals("LABEL", out.label()); // auto-mapped
      assertEquals("LABEL", out.inner().code()); // codegen-style telescope overlay
    }

    @Test
    @DisplayName("codegen-style nested-source telescope works with Mapping.to(Telescope, Accessor)")
    void codegenStyleNestedSourceWorks() {
      final var codegenStyleSrc = Telescope.<A, Inner>lens(A::inner, (s, v) -> new A(s.label(), v)).then(
        Telescope.<Inner, String>lens(Inner::code, (s, v) -> new Inner(v))
      );

      final var mapper = Telescope.mapper(A.class, B.class, to(codegenStyleSrc, B::label));

      final var out = mapper.forward(new A("ignored", new Inner("FROM-NESTED")));
      assertEquals("FROM-NESTED", out.label());
    }

    @Test
    @DisplayName("real @Focus-generated navigator works as the tgt telescope in Mapping.to(srcAcc, navigator.method())")
    void realFocusNavigatorWorksAsTgtTelescope() {
      // Use the actual @Focus-generated navigator chain end-to-end:
      //   PersonTelescope.of().address().city() : Telescope<Person, String>
      // The codegen-emitted .address() returns a typed step (AddressTelescope<Person>)
      // and .city() returns the underlying Telescope<Person, String> directly — no escape
      // hatch (.get() / .field(...)) needed. This is the codegen 1:1 promise at its strongest: the
      // generated navigator value is a first-class Mapping.to(...) target argument.
      //
      // Auto-recursion fills the same-named "address" subtree from src → tgt; the telescope row
      // then overlays the city leaf with the value read at a different source field
      // (Src::displayCity).
      record Src(String name, int age, Address address, String displayCity) {}

      final var mapper = Telescope.mapper(
        Src.class,
        Person.class,
        to(Src::displayCity, PersonTelescope.of().address().city())
      );

      final var out = mapper.forward(new Src("Alice", 30, new Address("ignored", "11201"), "Brooklyn"));
      assertEquals("Brooklyn", out.address().city()); // telescope overlay
      assertEquals("11201", out.address().zip()); // auto-mapped from Src::address.zip
      assertEquals("Alice", out.name());
      assertEquals(30, out.age());
    }

    @Test
    @DisplayName("real @Focus-generated navigators on BOTH sides of Mapping.to(Telescope, Telescope)")
    void bothSidesCodegenNavigators() {
      // Two same-shape @Focus fixtures (Person) — source and target both produce real
      // codegen navigators. The telescope row reads city at the source nav and writes city at the
      // target nav. Demonstrates Mapping.to(Telescope, Telescope) accepting codegen-emitted
      // Telescope<R, X> on both sides without any runtime escape hatch.
      final var srcCityPath = PersonTelescope.of().address().city();
      final var tgtCityPath = PersonTelescope.of().address().city();

      final var mapper = Telescope.mapper(Person.class, Person.class, to(srcCityPath, tgtCityPath));

      final var out = mapper.forward(new Person("Alice", 30, new Address("Brooklyn", "11201")));
      assertEquals("Brooklyn", out.address().city());
      assertEquals("11201", out.address().zip());
      assertEquals("Alice", out.name());
    }
  }

  @Nested
  @DisplayName("Mapping.to(Telescope, Accessor) — nested source → flat target")
  class NestedSrcToFlatTgt {

    @Test
    @DisplayName("forward reads at the nested source path and writes to the flat target accessor")
    void forwardReadsNestedSrc() {
      final var mapper = Telescope.mapper(
        A.class,
        B.class,
        to(Telescope.of(A.class).field(A::inner).field(Inner::code), B::label)
      );

      final var out = mapper.forward(new A("ignored", new Inner("FROM-NESTED")));
      assertEquals("FROM-NESTED", out.label()); // telescope read wins
      assertEquals("FROM-NESTED", out.inner().code()); // auto-mapped from A::inner.code
    }
  }

  @Nested
  @DisplayName("Mapping.to(Telescope, Telescope) — both nested, broadcast semantics on many-focus")
  class NestedSrcToNestedTgt {

    @Test
    @DisplayName("single-focus on both sides: read at src telescope, write at tgt telescope")
    void singleFocusBothSides() {
      final var mapper = Telescope.mapper(
        A.class,
        B.class,
        to(
          Telescope.of(A.class).field(A::inner).field(Inner::code),
          Telescope.of(B.class).field(B::inner).field(Inner::code)
        )
      );

      final var out = mapper.forward(new A("L", new Inner("NESTED-VAL")));
      assertEquals("NESTED-VAL", out.inner().code());
    }

    @Test
    @DisplayName("multiple to() rows with deeply-nested telescopes on BOTH sides — different top-level names")
    void multipleDeeplyNestedRowsCompose() {
      record Leaf(String value) {}
      record L2(Leaf leaf) {}
      record L1(L2 l2) {}
      record SrcDeep(L1 l1) {}
      record TgtDeep(L1 l1) {}
      record SrcTop(SrcDeep first, SrcDeep second) {}
      record TgtTop(TgtDeep a, TgtDeep b) {}

      // Top-level structure: SrcTop has `first`/`second`, TgtTop has `a`/`b` — different names AND
      // different leaf types. Two SameTypedTo-via rows handle the top-level rename + structure
      // initialization (SrcDeep → TgtDeep via the deep mapper). Two deeply-nested telescope rows
      // then overlay leaves with cross-routed values.
      //
      // Closest analogue to MapStruct's:
      //   @Mapping(source = "first",                       target = "a")
      //   @Mapping(source = "second",                      target = "b")
      //   @Mapping(source = "second.l1.l2.leaf.value",     target = "a.l1.l2.leaf.value")
      //   @Mapping(source = "first.l1.l2.leaf.value",      target = "b.l1.l2.leaf.value")
      final var deepMapper = Telescope.mapper(SrcDeep.class, TgtDeep.class);

      final var mapper = Telescope.mapper(
        SrcTop.class,
        TgtTop.class,
        Mapping.via(SrcTop::first, TgtTop::a, deepMapper),
        Mapping.via(SrcTop::second, TgtTop::b, deepMapper),
        to(
          Telescope.of(SrcTop.class)
            .field(SrcTop::second)
            .field(SrcDeep::l1)
            .field(L1::l2)
            .field(L2::leaf)
            .field(Leaf::value),
          Telescope.of(TgtTop.class)
            .field(TgtTop::a)
            .field(TgtDeep::l1)
            .field(L1::l2)
            .field(L2::leaf)
            .field(Leaf::value)
        ),
        to(
          Telescope.of(SrcTop.class)
            .field(SrcTop::first)
            .field(SrcDeep::l1)
            .field(L1::l2)
            .field(L2::leaf)
            .field(Leaf::value),
          Telescope.of(TgtTop.class)
            .field(TgtTop::b)
            .field(TgtDeep::l1)
            .field(L1::l2)
            .field(L2::leaf)
            .field(Leaf::value)
        )
      );

      final var out = mapper.forward(
        new SrcTop(
          new SrcDeep(new L1(new L2(new Leaf("ORIG-FIRST")))),
          new SrcDeep(new L1(new L2(new Leaf("ORIG-SECOND"))))
        )
      );
      // Top-level rename via Mapping.via produced the base, then telescope rows cross-routed
      // leaves.
      assertEquals("ORIG-SECOND", out.a().l1().l2().leaf().value());
      assertEquals("ORIG-FIRST", out.b().l1().l2().leaf().value());
    }
  }

  // ---------- Many-focus / collection fixtures ----------
  record Item(String name) {}

  record Cart(String tenant, List<Item> items) {}

  record CartDto(String tenant, List<Item> items) {}

  @Nested
  @DisplayName("Mapping.to(Accessor, Telescope) — broadcast on many-focus target")
  class BroadcastOnTarget {

    @Test
    @DisplayName("forward writes the single source value to every focus of the target telescope")
    void broadcasts() {
      final var mapper = Telescope.mapper(
        Cart.class,
        CartDto.class,
        to(Cart::tenant, Telescope.of(CartDto.class).each(CartDto::items).field(Item::name))
      );

      final var out = mapper.forward(new Cart("acme", List.of(new Item("a"), new Item("b"), new Item("c"))));
      assertEquals(List.of("acme", "acme", "acme"), out.items().stream().map(Item::name).toList());
    }
  }

  @Nested
  @DisplayName("Mapping.zip(Telescope, Telescope) — positional N:N between two many-focus telescopes")
  class ZipPositional {

    @Test
    @DisplayName("forward writes positionally when cardinality matches")
    void zipsWhenCardinalityMatches() {
      record SrcWrap(String tenant, List<Item> items) {}
      record TgtWrap(String tenant, List<Item> items) {}

      final var mapper = Telescope.mapper(
        SrcWrap.class,
        TgtWrap.class,
        zip(
          Telescope.of(SrcWrap.class).each(SrcWrap::items).field(Item::name),
          Telescope.of(TgtWrap.class).each(TgtWrap::items).field(Item::name)
        )
      );

      final var out = mapper.forward(new SrcWrap("t", List.of(new Item("x"), new Item("y"), new Item("z"))));
      assertEquals(List.of("x", "y", "z"), out.items().stream().map(Item::name).toList());
    }

    @Test
    @DisplayName("cardinality mismatch throws on apply")
    void cardinalityMismatchThrows() {
      // Force a mismatch: source and target list sizes diverge via a typed transform on the list.
      // The transform shrinks 3 source items to 2 target items; zip then sees src.count=3 vs
      // tgt.count=2 and throws.
      record SrcWrap(String tenant, List<Item> items) {}
      record TgtWrap(String tenant, List<Item> items) {}

      final var mapper = Telescope.mapper(
        SrcWrap.class,
        TgtWrap.class,
        Mapping.<SrcWrap, TgtWrap, List<Item>, List<Item>>to(
          SrcWrap::items,
          TgtWrap::items,
          xs -> xs.stream().limit(2).toList(), // shrink to 2
          ys -> ys
        ),
        zip(
          Telescope.of(SrcWrap.class).each(SrcWrap::items).field(Item::name),
          Telescope.of(TgtWrap.class).each(TgtWrap::items).field(Item::name)
        )
      );

      final var ex = assertThrows(IllegalStateException.class, () ->
        mapper.forward(new SrcWrap("t", List.of(new Item("x"), new Item("y"), new Item("z"))))
      );
      assertEquals(true, ex.getMessage().contains("cardinality must match"));
    }
  }
}
