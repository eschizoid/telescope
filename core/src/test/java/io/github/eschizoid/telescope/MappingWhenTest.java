package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.mapping.Mapping;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Mapping.when(predicate, inner)} — the whole-source predicate-gated wrapper around
 * telescope-based rows. Closes MapStruct's {@code @Condition} for cases where the predicate
 * operates on the entire source (not a single field). Forward-only by design — backward direction
 * skips the row entirely, matching the retraction semantics of {@code Constant} / {@code Compute}.
 */
class MappingWhenTest {

  record Address(String city, String country) {}

  record Order(String id, Address shipping, int priority) {}

  record OrderDto(String id, String shipCity, String shipCountry, boolean expediteFlag, String tenant) {}

  @Nested
  @DisplayName("when(predicate, to(srcAcc, targetTelescope)) — gated flat→nested write")
  class GatedFlatToNested {

    record Src(String name, String email) {}

    record Wrapper(String value) {}

    record Dst(String name, Wrapper wrapped) {}

    @Test
    @DisplayName("predicate true → inner TelescopeTo applies; predicate false → target field stays at default")
    void predicateGatesTelescopeTo() {
      final var emailTelescope = Telescope.of(Dst.class).field(Dst::wrapped).field(Wrapper::value);
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        to(Src::name, Dst::name),
        when(s -> s.email() != null && !s.email().isBlank(), to(Src::email, emailTelescope))
      );

      // predicate true → email written deep into wrapped.value
      assertEquals(new Dst("Alice", new Wrapper("alice@ex.com")), mapper.forward(new Src("Alice", "alice@ex.com")));

      // predicate false (null email) → row skipped, wrapped stays at recursive default (null
      // inside)
      final var skipped = mapper.forward(new Src("Bob", null));
      assertEquals("Bob", skipped.name());
      assertNull(skipped.wrapped().value(), "skipped row → leaf at recursive default");

      // predicate false (blank email) → same skip path
      final var blankSkipped = mapper.forward(new Src("Carol", "   "));
      assertNull(blankSkipped.wrapped().value());
    }

    @Test
    @DisplayName("backward direction unconditionally skips the conditional row")
    void backwardSkipsRow() {
      final var emailTelescope = Telescope.of(Dst.class).field(Dst::wrapped).field(Wrapper::value);
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        to(Src::name, Dst::name),
        when(s -> s.email() != null, to(Src::email, emailTelescope))
      );

      // Backward: the email slot on the rebuilt Src stays at type default (null) — Conditional
      // doesn't contribute to the source rebuild, matching Constant/Compute retraction semantics.
      final var dst = new Dst("Alice", new Wrapper("alice@ex.com"));
      assertEquals(new Src("Alice", null), mapper.backward(dst));
    }
  }

  @Nested
  @DisplayName("when(predicate, constant(targetTelescope, value)) — gated stamping")
  class GatedConstant {

    @Test
    @DisplayName("high-priority orders get expediteFlag stamped; low-priority orders do not")
    void prioritySkippedFlag() {
      final java.util.function.Predicate<Order> highPriority = o -> o.priority() >= 10;
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        to(Order::id, OrderDto::id),
        when(highPriority, constant(OrderDto::expediteFlag, true))
      );

      final var lowPrio = new Order("ORD-1", new Address("Chicago", "US"), 1);
      final var highPrio = new Order("ORD-2", new Address("Chicago", "US"), 99);

      assertEquals(false, mapper.forward(lowPrio).expediteFlag(), "low priority → stamp skipped");
      assertEquals(true, mapper.forward(highPrio).expediteFlag(), "high priority → stamped");
    }
  }

  @Nested
  @DisplayName("when(predicate, compute(targetTelescope, supplier)) — gated lazy stamping")
  class GatedCompute {

    @Test
    @DisplayName("supplier fires only when predicate accepts source")
    void supplierFiresOnlyOnAccept() {
      final var calls = new AtomicInteger();
      final java.util.function.Predicate<Order> highPriority = o -> o.priority() >= 10;
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        to(Order::id, OrderDto::id),
        when(
          highPriority,
          compute(OrderDto::tenant, () -> {
            calls.incrementAndGet();
            return "expedite-tenant";
          })
        )
      );

      // low priority → predicate rejects, supplier NOT invoked
      mapper.forward(new Order("a", new Address("Chicago", "US"), 1));
      assertEquals(0, calls.get(), "supplier should not fire when predicate rejects");

      // high priority → supplier fires once
      final var hi = mapper.forward(new Order("b", new Address("Chicago", "US"), 50));
      assertEquals("expedite-tenant", hi.tenant());
      assertEquals(1, calls.get());
    }
  }

  @Nested
  @DisplayName("when(predicate, to(srcTelescope, tgtAcc)) — gated nested→flat read")
  class GatedNestedToFlat {

    @Test
    @DisplayName("predicate true → deep read applies; predicate false → target field stays at default")
    void predicateGatesFromTelescopeTo() {
      final var cityTelescope = Telescope.of(Order.class).field(Order::shipping).field(Address::city);
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        to(Order::id, OrderDto::id),
        when(o -> o.shipping() != null, to(cityTelescope, OrderDto::shipCity))
      );

      final var withShip = new Order("ORD-1", new Address("Chicago", "US"), 1);
      final var noShip = new Order("ORD-2", null, 1);

      assertEquals("Chicago", mapper.forward(withShip).shipCity());
      assertNull(mapper.forward(noShip).shipCity(), "no shipping → row skipped → leaf stays null");
    }
  }

  @Nested
  @DisplayName("when(predicate, to(srcTelescope, tgtTelescope)) — gated nested→nested write")
  class GatedNestedToNested {

    @Test
    @DisplayName("broadcast deep write only fires when predicate accepts")
    void predicateGatesTelescopeToTelescope() {
      final var srcCity = Telescope.of(Order.class).field(Order::shipping).field(Address::city);
      final var tgtCity = Telescope.of(OrderDto.class).field(OrderDto::shipCity);
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        to(Order::id, OrderDto::id),
        when(o -> o.shipping() != null, to(srcCity, tgtCity))
      );

      assertEquals("Berlin", mapper.forward(new Order("a", new Address("Berlin", "DE"), 1)).shipCity());
      assertNull(mapper.forward(new Order("b", null, 1)).shipCity());
    }
  }

  @Nested
  @DisplayName("when(predicate, zip(srcTelescope, tgtTelescope)) — gated positional zip")
  class GatedZip {

    record Cart(boolean active, List<String> items) {}

    record CartDto(boolean active, List<String> items) {}

    @Test
    @DisplayName("zip-Kind row composes cleanly under Conditional — same dispatch as broadcast")
    void predicateGatesZip() {
      // Same-name 'items' on both sides so auto-recursion populates target.items from source.items
      // (identity copy). The when(zip(...)) row then conditionally re-applies a positional overlay
      // on top — when predicate accepts, zip runs (identity over the already-copied list, no-op);
      // when rejects, zip skipped. The test pins that Kind.ZIP routes through Conditional without
      // throwing — the Kind.BROADCAST shape is exercised separately in GatedNestedToNested.
      final var srcItems = Telescope.of(Cart.class).each(Cart::items);
      final var tgtItems = Telescope.of(CartDto.class).each(CartDto::items);
      final var mapper = Telescope.mapper(
        Cart.class,
        CartDto.class,
        to(Cart::active, CartDto::active),
        when(Cart::active, zip(srcItems, tgtItems))
      );

      assertEquals(
        new CartDto(true, List.of("a", "b", "c")),
        mapper.forward(new Cart(true, List.of("a", "b", "c"))),
        "active → zip applies on top of auto-copy"
      );
      assertEquals(
        new CartDto(false, List.of("x", "y", "z")),
        mapper.forward(new Cart(false, List.of("x", "y", "z"))),
        "inactive → zip skipped, auto-copy stands"
      );
    }
  }

  @Nested
  @DisplayName("Composable predicates — Predicate#and / Predicate#or / negate")
  class ComposablePredicates {

    record Src(String name, Integer score) {}

    record Wrapper(Integer value) {}

    record Dst(String name, Wrapper scoreWrap) {}

    @Test
    @DisplayName("predicate composition with and() — both conditions gate the inner row")
    void composeAnd() {
      final var scoreTel = Telescope.of(Dst.class).field(Dst::scoreWrap).field(Wrapper::value);
      final java.util.function.Predicate<Src> positiveScore = s -> s.score() != null && s.score() > 0;
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        to(Src::name, Dst::name),
        when(positiveScore.and(s -> s.name() != null), to(Src::score, scoreTel))
      );

      // both true → score lands in scoreWrap.value
      assertEquals(42, mapper.forward(new Src("Alice", 42)).scoreWrap().value(), "both true → applies");
      // score == -1 → first predicate fails → row skipped → leaf stays at recursive default (null)
      assertNull(mapper.forward(new Src("Bob", -1)).scoreWrap().value(), "score not > 0 → skipped");
      // name null → second predicate fails → row skipped → leaf stays at recursive default (null)
      assertNull(mapper.forward(new Src(null, 42)).scoreWrap().value(), "name null → skipped");
    }
  }

  @Nested
  @DisplayName("Backward direction — Conditional rows skip unconditionally across all inner shapes")
  class BackwardSkipsAllShapes {

    record Src(String name, String email, String region) {}

    record Wrapper(String value) {}

    record Dst(String name, Wrapper emailWrap, String region) {}

    @Test
    @DisplayName("backward skips conditional FromTelescopeTo — source field stays at rebuild default")
    void backwardSkipsFromTelescopeTo() {
      final var emailDeep = Telescope.of(Src.class).field(Src::email);
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        to(Src::name, Dst::name),
        to(Src::region, Dst::region),
        when(s -> s.email() != null, to(emailDeep, Dst::region))
      );

      // Forward: Src.region overrides Dst.region (last write wins on target field 'region').
      // Predicate evaluated against the Src — we don't care about exact forward here, only that
      // backward direction skips the Conditional row entirely.
      final var dst = new Dst("Alice", new Wrapper("ignored"), "rebuiltRegion");
      final var s = mapper.backward(dst);
      // 'region' on source rebuilt via plain to(Src::region, Dst::region) — the Conditional from
      // emailDeep → Dst::region contributes NOTHING to the source rebuild because Conditional is
      // forward-only.
      assertEquals("rebuiltRegion", s.region(), "non-conditional row wins source rebuild");
    }

    @Test
    @DisplayName("backward skips conditional Compute — supplier NOT invoked on backward")
    void backwardSkipsCompute() {
      record SimpleSrc(String id) {}
      record SimpleDst(String id, String stamped) {}

      final var calls = new AtomicInteger();
      final java.util.function.Predicate<SimpleSrc> always = s -> true;
      final var mapper = Telescope.mapper(
        SimpleSrc.class,
        SimpleDst.class,
        to(SimpleSrc::id, SimpleDst::id),
        when(
          always,
          compute(SimpleDst::stamped, () -> {
            calls.incrementAndGet();
            return "fresh";
          })
        )
      );

      mapper.backward(new SimpleDst("a", "ignored"));
      assertEquals(0, calls.get(), "supplier MUST NOT fire on backward — Conditional is forward-only");
    }

    @Test
    @DisplayName("backward skips conditional zip — no cardinality enforcement on backward")
    void backwardSkipsZip() {
      record Cart(boolean active, List<String> items) {}
      record CartDto(boolean active, List<String> items) {}

      final var srcItems = Telescope.of(Cart.class).each(Cart::items);
      final var tgtItems = Telescope.of(CartDto.class).each(CartDto::items);
      final var mapper = Telescope.mapper(
        Cart.class,
        CartDto.class,
        to(Cart::active, CartDto::active),
        when(Cart::active, zip(srcItems, tgtItems))
      );

      // Backward direction skips the zip row entirely — no cardinality check fires, even when the
      // forward path would have rejected the call. Auto-recursion rebuilds Cart.items from
      // CartDto.items by same-name match.
      final var c = mapper.backward(new CartDto(true, List.of("x", "y")));
      assertEquals(new Cart(true, List.of("x", "y")), c);
    }
  }

  @Nested
  @DisplayName("Self-recursive types — Conditional cycles cleanly through the lattice")
  class SelfRecursiveCycle {

    record Node(String value, Node child) {}

    record NodeDto(String value, NodeDto child, String flag) {}

    @Test
    @DisplayName("Conditional row on top-level pair fires once; nested Node→NodeDto recursion uses base Iso")
    void conditionalAtRootOnly() {
      final java.util.function.Predicate<Node> isRoot = n -> n.value().equals("root");
      final var mapper = Telescope.mapper(Node.class, NodeDto.class, when(isRoot, constant(NodeDto::flag, "ROOT")));

      final var leaf = new Node("leaf", null);
      final var mid = new Node("mid", leaf);
      final var root = new Node("root", mid);

      final var dto = mapper.forward(root);

      // Top-level: predicate accepts root → flag stamped
      assertEquals("ROOT", dto.flag());
      // Nested recursion populated value + child chain; nested NodeDto.flag stays at default (null)
      // because the Conditional row pins to the top-level (Node, NodeDto) pair only.
      assertEquals("root", dto.value());
      assertEquals("mid", dto.child().value());
      assertNull(dto.child().flag(), "nested NodeDto.flag stays at recursive default — predicate didn't fire here");
      assertEquals("leaf", dto.child().child().value());
      assertNull(dto.child().child().flag());
    }
  }

  @Nested
  @DisplayName("Predicate-throws decoration — failure points at the when(...) call site")
  class PredicateThrowsDecoration {

    record Src(String name) {}

    record Dst(String name, String stamped) {}

    @Test
    @DisplayName("predicate NPE wrapped in IllegalStateException naming inner-kind + source field")
    void decoratedExceptionNamesInnerAndField() {
      final java.util.function.Predicate<Src> brokenPredicate = s -> s.name().length() > 0;
      // ^ throws NPE when name is null; the row's inner is Constant whose targetField is "stamped".
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        to(Src::name, Dst::name),
        when(brokenPredicate, constant(Dst::stamped, "X"))
      );

      final var ex = assertThrows(IllegalStateException.class, () -> mapper.forward(new Src(null)));
      final var msg = ex.getMessage();
      assertEquals(true, msg.contains("Mapping.when"), "message names the construct");
      assertEquals(true, msg.contains("Constant"), "message names the inner kind");
      assertEquals(true, ex.getCause() instanceof NullPointerException, "original predicate cause preserved");
    }
  }

  @Nested
  @DisplayName("Pinning contract — Conditional metadata always returns null (top-level pinning)")
  class PinningContract {

    record Src(String name) {}

    record Dst(String name) {}

    @Test
    @DisplayName("Conditional.sourceClass / targetClass / sourceField / targetField all null")
    void pinsTopLevelOnly() {
      final var nameTel = Telescope.of(Dst.class).field(Dst::name);
      final var conditional = when((java.util.function.Predicate<Src>) s -> true, to(Src::name, nameTel));

      // Pin the documented contract — every supported inner row is telescope-based, so the
      // Conditional always pins to the outer (topSource, topTarget) pair via null-class routing.
      assertNull(conditional.sourceClass(), "Conditional.sourceClass MUST be null — pins top-level");
      assertNull(conditional.targetClass(), "Conditional.targetClass MUST be null — pins top-level");
      assertNull(
        conditional.sourceField(),
        "Conditional.sourceField MUST be null — wrapper contributes no field claim"
      );
      assertNull(
        conditional.targetField(),
        "Conditional.targetField MUST be null — wrapper contributes no field claim"
      );
    }
  }

  @Nested
  @DisplayName("Construction-time rejection — invalid inner rows")
  class ConstructionRejection {

    record Src(String name) {}

    record Dst(String name) {}

    @Test
    @DisplayName("field-iso row (plain to(srcAcc, tgtAcc)) rejected with clear error pointing at toOrElse")
    void rejectsFieldIsoInner() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        when((java.util.function.Predicate<Src>) s -> true, to(Src::name, Dst::name))
      );
      final var msg = ex.getMessage();
      assertEquals(true, msg.contains("toOrElse"), "error suggests the field-level alternative");
      assertEquals(true, msg.contains("telescope-based"), "error names the supported shape");
      assertEquals(true, msg.contains("SameTypedTo"), "error names the rejected kind");
      assertEquals(
        true,
        msg.contains("name → name"),
        "error names the actual field claim so the user can find the row"
      );
    }

    @Test
    @DisplayName("forward-only row rejected with hint pointing at fold-the-predicate-into-fn")
    void rejectsForwardOnlyTransformTo() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        when(
          (java.util.function.Predicate<Src>) s -> true,
          io.github.eschizoid.telescope.mapping.Mapping.forward(Src::name, Dst::name, String::toUpperCase)
        )
      );
      final var msg = ex.getMessage();
      assertEquals(true, msg.contains("Mapping.forward"), "error names the forward-only construct");
      assertEquals(
        true,
        msg.contains("fold the predicate"),
        "error suggests inlining the predicate into the forward function"
      );
      assertEquals(true, msg.contains("ForwardOnlyTransformTo"), "error names the rejected kind");
    }

    @Test
    @DisplayName("nested Conditional rejected — combine via Predicate#and/or instead")
    void rejectsNestedConditional() {
      final var emailTel = Telescope.of(Dst.class).field(Dst::name);
      final Mapping<Src, Dst> inner = when(
        (java.util.function.Predicate<Src>) s -> s.name() != null,
        to(Src::name, emailTel)
      );
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        when((java.util.function.Predicate<Src>) s -> true, inner)
      );
      assertEquals(true, ex.getMessage().contains("does not nest"), "error explains nesting is rejected");
    }

    @Test
    @DisplayName("null predicate rejected")
    void rejectsNullPredicate() {
      final var emailTel = Telescope.of(Dst.class).field(Dst::name);
      assertThrows(NullPointerException.class, () -> when(null, to(Src::name, emailTel)));
    }

    @Test
    @DisplayName("null inner rejected")
    void rejectsNullInner() {
      assertThrows(NullPointerException.class, () -> when((java.util.function.Predicate<Src>) s -> true, null));
    }
  }
}
