package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code Mapper.withPath} family — telescope's analogue of MapStruct's
 * {@code @Mapping(source = "a.b.c", target = "x.y.z")} for nested-field correspondences. Phase 1
 * covers scalar/single-focus paths on either side; Phase 2 covers many-focus broadcast and
 * positional zip.
 */
class NestedPathMapperTest {

  // ---------- Phase 1 fixtures ----------
  record A(String code, Inner inner) {}

  record Inner(String name) {}

  record B(String code, BInner inner) {}

  record BInner(String code) {}

  @Nested
  @DisplayName("Phase 1 — single-focus paths")
  class Phase1 {

    /** Hand-rolled base mapper so the tests isolate withPath from the auto-recursion logic. */
    private Mapper<A, B> baseMapper() {
      return Mapper.create(
        (final A a) -> new B(a.code(), new BInner("")),
        (final B b) -> new A(b.code(), new Inner("")),
        A.class,
        B.class,
        Map.of()
      );
    }

    @Test
    @DisplayName("flat source accessor → nested target path stamps the value at the leaf")
    void flatSourceToNestedTargetPath() {
      final var mapper = baseMapper().withPath(A::code, Telescope.of(B.class).field(B::inner).field(BInner::code));

      final var result = mapper.forward(new A("X", new Inner("n")));
      assertEquals("X", result.inner().code());
    }

    @Test
    @DisplayName("flat source accessor → nested target path round-trips through backward")
    void flatSourceToNestedTargetPathBackward() {
      final var mapper = baseMapper().withPath(A::code, Telescope.of(B.class).field(B::inner).field(BInner::code));

      final var b = new B("X", new BInner("X"));
      final var roundTripped = mapper.backward(b);
      assertEquals("X", roundTripped.code());
    }

    @Test
    @DisplayName("nested source path → nested target path round-trips")
    void nestedSourcePathToNestedTargetPath() {
      final var mapper = baseMapper().withPath(
        Telescope.of(A.class).field(A::inner).field(Inner::name),
        Telescope.of(B.class).field(B::inner).field(BInner::code)
      );

      final var result = mapper.forward(new A("X", new Inner("from-nested")));
      assertEquals("from-nested", result.inner().code());
    }

    @Test
    @DisplayName("multiple withPath calls layer N nested rules")
    void multipleWithPathCalls() {
      record A2(String a, String b) {}
      record Inner2(String inner) {}
      record Outer2(Inner2 first, Inner2 second) {}

      final var base = Mapper.create(
        (final A2 src) -> new Outer2(new Inner2(""), new Inner2("")),
        (final Outer2 t) -> new A2("", ""),
        A2.class,
        Outer2.class,
        Map.of()
      );
      final var mapper = base
        .withPath(A2::a, Telescope.of(Outer2.class).field(Outer2::first).field(Inner2::inner))
        .withPath(A2::b, Telescope.of(Outer2.class).field(Outer2::second).field(Inner2::inner));

      final var out = mapper.forward(new A2("A-val", "B-val"));
      assertEquals("A-val", out.first().inner());
      assertEquals("B-val", out.second().inner());
    }
  }

  // ---------- Phase 2 fixtures ----------
  record C(String tenant, List<Item> items) {}

  record Item(String name) {}

  record D(String tenant, List<Product> products) {}

  record Product(String name, String tenant) {}

  @Nested
  @DisplayName("Phase 2 — many-focus paths (broadcast and zip)")
  class Phase2 {

    @Test
    @DisplayName("broadcast: single source value written to every focus of a many-focus target")
    void broadcastSingleToMany() {
      final var base = Mapper.create(
        (final C c) ->
          new D(
            c.tenant(),
            c
              .items()
              .stream()
              .map(i -> new Product(i.name(), ""))
              .toList()
          ),
        (final D d) ->
          new C(
            d.tenant(),
            d
              .products()
              .stream()
              .map(p -> new Item(p.name()))
              .toList()
          ),
        C.class,
        D.class,
        Map.of()
      );

      final var mapper = base.withBroadcastPath(
        Telescope.of(C.class).field(C::tenant),
        Telescope.of(D.class).each(D::products).field(Product::tenant)
      );

      final var c = new C("acme", List.of(new Item("a"), new Item("b"), new Item("c")));
      final var d = mapper.forward(c);
      assertEquals(List.of("acme", "acme", "acme"), d.products().stream().map(Product::tenant).toList());
    }

    @Test
    @DisplayName("zip: positional N-to-N when source path and target path have matching cardinality")
    void zipPositional() {
      record SrcWrapper(List<Item> items) {}
      record TgtWrapper(List<Product> products) {}

      final var base = Mapper.create(
        (final SrcWrapper s) ->
          new TgtWrapper(
            s
              .items()
              .stream()
              .map(i -> new Product("", ""))
              .toList()
          ),
        (final TgtWrapper t) ->
          new SrcWrapper(
            t
              .products()
              .stream()
              .map(p -> new Item(""))
              .toList()
          ),
        SrcWrapper.class,
        TgtWrapper.class,
        Map.of()
      );

      final var mapper = base.withZipPath(
        Telescope.of(SrcWrapper.class).each(SrcWrapper::items).field(Item::name),
        Telescope.of(TgtWrapper.class).each(TgtWrapper::products).field(Product::name)
      );

      final var src = new SrcWrapper(List.of(new Item("x"), new Item("y"), new Item("z")));
      final var tgt = mapper.forward(src);
      assertEquals(List.of("x", "y", "z"), tgt.products().stream().map(Product::name).toList());
    }

    @Test
    @DisplayName("zip: cardinality mismatch throws")
    void zipCardinalityMismatch() {
      record SrcWrapper(List<Item> items) {}
      record TgtWrapper(List<Product> products) {}

      // Base mapper deliberately produces a DIFFERENT cardinality on the target side.
      final var base = Mapper.create(
        (final SrcWrapper s) -> new TgtWrapper(List.of(new Product("", ""), new Product("", ""))), // always 2
        (final TgtWrapper t) -> new SrcWrapper(List.of()),
        SrcWrapper.class,
        TgtWrapper.class,
        Map.of()
      );

      final var mapper = base.withZipPath(
        Telescope.of(SrcWrapper.class).each(SrcWrapper::items).field(Item::name),
        Telescope.of(TgtWrapper.class).each(TgtWrapper::products).field(Product::name)
      );

      final var src = new SrcWrapper(List.of(new Item("x"), new Item("y"), new Item("z"))); // 3 items
      final var ex = assertThrows(IllegalStateException.class, () -> mapper.forward(src));
      assertEquals(true, ex.getMessage().contains("cardinality must match"));
    }
  }
}
