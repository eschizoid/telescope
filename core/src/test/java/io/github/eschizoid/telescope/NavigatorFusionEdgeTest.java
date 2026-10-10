package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.NativeImage;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shapes around navigator fusion where fusion must decline, keep edit order, or route through a
 * particular rebuild: strict prefixes, equal paths, {@code then} joins, set containers that
 * collapse, filters, constructor and builder beans, and the {@code componentLens} seam itself.
 * Every case compares the fused result with the sequential fold.
 */
class NavigatorFusionEdgeTest {

  private static FusionCrew crew() {
    final var ann = new FusionMember("Ann", "ANN@X.IO", 30);
    final var bo = new FusionMember("Bo", "BO@X.IO", 25);
    return new FusionCrew("core", List.of(ann, bo), Map.of("lead", ann), Optional.of(bo), ann, new AtomicInteger());
  }

  @SafeVarargs
  @SuppressWarnings("varargs")
  private static <S> boolean fuses(final Edit<S>... edits) {
    return Fusion.fuse(edits) != null;
  }

  @SafeVarargs
  private static <S> S sequential(final S source, final Edit<S>... edits) {
    var s = source;
    for (final var e : edits) s = e.apply(s);
    return s;
  }

  private static void sameCrew(final FusionCrew expected, final FusionCrew actual) {
    assertEquals(expected.label(), actual.label());
    assertEquals(expected.members(), actual.members());
    assertEquals(expected.byRole(), actual.byRole());
    assertEquals(expected.lead(), actual.lead());
    assertEquals(expected.captain(), actual.captain());
  }

  @Test
  @DisplayName("equal navigator paths compose their functions in edit order")
  void equalNavigatorPathsComposeInEditOrder() {
    final var a = over(FusionCrewTelescope.of().label(), (final String s) -> s + "a");
    final var b = over(FusionCrewTelescope.of().label(), String::toUpperCase);
    assertTrue(fuses(a, b));
    assertEquals("COREA", Telescope.all(a, b).apply(crew()).label());
    assertEquals("COREa", Telescope.all(b, a).apply(crew()).label());
    sameCrew(sequential(crew(), b, a), Telescope.all(b, a).apply(crew()));
  }

  @Test
  @DisplayName("equal mixed paths under each, eachValue and whenPresent compose in edit order")
  void equalPathsInsideEachEachValueWhenPresentComposeInOrder() {
    final var n1 = over(FusionCrewTelescope.of().members().each().name(), (final String s) -> s + "1");
    final var n2 = over(
      Telescope.of(FusionCrew.class).each(FusionCrew::members).field(FusionMember::name),
      String::toUpperCase
    );
    final var e1 = over(FusionCrewTelescope.of().byRole().eachValue().email(), (final String s) -> s + "x");
    final var e2 = over(
      Telescope.of(FusionCrew.class).eachValue(FusionCrew::byRole).field(FusionMember::email),
      String::toLowerCase
    );
    final var a1 = over(FusionCrewTelescope.of().lead().whenPresent().age(), (final Integer i) -> i * 2);
    final var a2 = over(
      Telescope.of(FusionCrew.class).whenPresent(FusionCrew::lead).field(FusionMember::age),
      (final Integer i) -> i + 1
    );
    assertTrue(fuses(n1, e1, a1, n2, e2, a2));
    sameCrew(sequential(crew(), n1, e1, a1, n2, e2, a2), Telescope.all(n1, e1, a1, n2, e2, a2).apply(crew()));
    sameCrew(sequential(crew(), n2, e2, a2, n1, e1, a1), Telescope.all(n2, e2, a2, n1, e1, a1).apply(crew()));
    assertEquals(51, Telescope.all(n1, e1, a1, n2, e2, a2).apply(crew()).lead().orElseThrow().age());
  }

  @Test
  @DisplayName("a navigator path that is a strict prefix of another declines fusion")
  void strictPrefixNavigatorPathDeclinesAndMatchesSequential() {
    final var whole = over(FusionCrewTelescope.of().captain().get(), (final FusionMember m) ->
      new FusionMember("Zed", m.email(), m.age())
    );
    final var name = over(FusionCrewTelescope.of().captain().name(), (final String n) -> n + "!");
    final var handWhole = over(Telescope.of(FusionCrew.class).field(FusionCrew::captain), (final FusionMember m) ->
      new FusionMember("Q", m.email(), m.age())
    );
    assertFalse(fuses(whole, name));
    assertFalse(fuses(name, whole));
    assertFalse(fuses(name, handWhole));
    assertEquals("Zed!", Telescope.all(whole, name).apply(crew()).captain().name());
    assertEquals("Zed", Telescope.all(name, whole).apply(crew()).captain().name());
    assertEquals("Q", Telescope.all(name, handWhole).apply(crew()).captain().name());
  }

  @Test
  @DisplayName("set edits that collapse two elements into one fuse and match the sequential fold")
  void setContainerNavigatorWithCollapsingEditsMatchesSequential() {
    final var tags = new LinkedHashSet<FusionMember>();
    tags.add(new FusionMember("a", "a@x", 1));
    tags.add(new FusionMember("b", "b@x", 1));
    final var src = new FusionTagged("t", tags, new FusionMember("o", "o@x", 2));
    final var name = over(FusionTaggedTelescope.of().tags().each().name(), (final String s) -> "X");
    final var email = over(
      Telescope.of(FusionTagged.class).each(FusionTagged::tags).field(FusionMember::email),
      (final String s) -> "same"
    );
    final var label = over(FusionTaggedTelescope.of().label(), String::toUpperCase);
    final var owner = over(FusionTaggedTelescope.of().owner().age(), (final Integer i) -> i + 1);
    assertTrue(fuses(name, email, label, owner));
    assertEquals(sequential(src, name, email, label, owner), Telescope.all(name, email, label, owner).apply(src));
    assertEquals(1, Telescope.all(name, email, label, owner).apply(src).tags().size());
  }

  @Test
  @DisplayName("then joins navigator and hand-written paths in either order into the hand-written hop keys")
  void thenJoinsNavigatorAndRuntimePathsBothWays() {
    final var runtimeThenNav = Telescope.of(FusionCrew.class)
      .field(FusionCrew::captain)
      .then(FusionMemberTelescope.of().name());
    final var navThenRuntime = FusionCrewTelescope.of()
      .captain()
      .get()
      .then(Telescope.of(FusionMember.class).field(FusionMember::name));
    final var hand = Telescope.of(FusionCrew.class).field(FusionCrew::captain).field(FusionMember::name);
    final var handKeys = hand.hops.stream().map(Fusion.Hop::key).toList();
    assertEquals(handKeys, runtimeThenNav.hops.stream().map(Fusion.Hop::key).toList());
    assertEquals(handKeys, navThenRuntime.hops.stream().map(Fusion.Hop::key).toList());
    final var a = over(runtimeThenNav, (final String s) -> s + "a");
    final var b = over(navThenRuntime, (final String s) -> s + "b");
    final var c = over(FusionCrewTelescope.of().captain().email(), String::toLowerCase);
    assertTrue(fuses(a, b, c));
    sameCrew(sequential(crew(), a, b, c), Telescope.all(a, b, c).apply(crew()));
    assertEquals("Annab", Telescope.all(a, b, c).apply(crew()).captain().name());
    assertEquals("Annba", Telescope.all(b, a, c).apply(crew()).captain().name());
  }

  @Test
  @DisplayName("then with a side that records no hops leaves the whole path unfused")
  void thenWithAnUndescribedSideStaysUnfused() {
    final var custom = Telescope.lens(FusionMember::name, (final FusionMember m, final String n) ->
      new FusionMember(n.toUpperCase(), m.email(), m.age())
    );
    assertNull(Telescope.of(FusionCrew.class).field(FusionCrew::captain).then(custom).hops);
    assertNull(FusionCrewTelescope.of().captain().get().then(custom).hops);
    assertNull(FusionCrewTelescope.of().label().after(String::trim).hops);
    assertNull(FusionCrewTelescope.of().label().before(String::trim).hops);
    final var weird = over(FusionCrewTelescope.of().captain().get().then(custom), (final String s) -> s + "z");
    final var plain = over(FusionCrewTelescope.of().captain().name(), (final String s) -> s + "y");
    assertFalse(fuses(weird, plain));
    assertEquals("ANNZy", Telescope.all(weird, plain).apply(crew()).captain().name());
  }

  @Test
  @DisplayName("navigator tails under one filter replay per edit and match the sequential fold")
  void filterReplayAcrossNavigatorTailsMatchesSequential() {
    final Predicate<FusionMember> older = m -> m.age() > 26;
    final var base = Telescope.of(FusionCrew.class).each(FusionCrew::members).filter(older);
    final var age = over(base.then(FusionMemberTelescope.of().age()), (final Integer a) -> a - 10);
    final var name = over(base.then(FusionMemberTelescope.of().name()), (final String n) -> n + "!");
    assertTrue(fuses(age, name));
    sameCrew(sequential(crew(), age, name), Telescope.all(age, name).apply(crew()));
    assertEquals("Ann", Telescope.all(age, name).apply(crew()).members().get(0).name());
  }

  @Test
  @DisplayName("constructor and builder bean navigators fuse with hand-written bean paths")
  void ctorAndBuilderBeanNavigatorsFuseAndMatchSequential() {
    final var t1 = over(FusionCtorBeanTelescope.of().title(), (final String s) -> s + "1");
    final var t2 = over(Telescope.ofBean(FusionCtorBean.class).field(FusionCtorBean::getTitle), String::toUpperCase);
    final var c1 = over(FusionCtorBeanTelescope.of().code(), (final String s) -> s + "c");
    assertTrue(fuses(t1, c1, t2));
    final var src = new FusionCtorBean("t", "k");
    final var seq = sequential(src, t1, c1, t2);
    final var fused = Telescope.all(t1, c1, t2).apply(src);
    assertEquals(seq.getTitle(), fused.getTitle());
    assertEquals(seq.getCode(), fused.getCode());
    assertEquals("T1", fused.getTitle());

    final var b1 = over(FusionBuilderBeanTelescope.of().title(), (final String s) -> s + "1");
    final var b2 = over(
      Telescope.ofBean(FusionBuilderBean.class).field(FusionBuilderBean::getTitle),
      String::toUpperCase
    );
    final var bc = over(FusionBuilderBeanTelescope.of().code(), (final String s) -> s + "c");
    assertTrue(fuses(b2, bc, b1));
    final var bsrc = FusionBuilderBean.builder().title("t").code("k").build();
    final var bseq = sequential(bsrc, b2, bc, b1);
    final var bfused = Telescope.all(b2, bc, b1).apply(bsrc);
    assertEquals(bseq.getTitle(), bfused.getTitle());
    assertEquals(bseq.getCode(), bfused.getCode());
    assertEquals("T1", bfused.getTitle());
  }

  @Test
  @DisplayName("componentLens names a getter inherited from a superclass by the class it navigates")
  void componentLensOwnerIsTheReceiver() {
    final var seam = Telescope.componentLens(
      FusionInheritingBean.class,
      "note",
      FusionInheritingBean::getNote,
      (final FusionInheritingBean b, final String n) -> {
        final var copy = new FusionInheritingBean();
        copy.setNote(n);
        return copy;
      }
    );
    final var hand = Telescope.ofBean(FusionInheritingBean.class).field(FusionInheritingBean::getNote);
    assertEquals(hand.hops.stream().map(Fusion.Hop::key).toList(), seam.hops.stream().map(Fusion.Hop::key).toList());
    assertEquals(FusionInheritingBean.class, seam.hops.get(0).owner());
  }

  @Test
  @DisplayName("componentLens refuses a component name its getter does not read")
  void componentLensRefusesAMismatchedComponent() {
    final var e = assertThrows(IllegalArgumentException.class, () ->
      Telescope.componentLens(FusionMember.class, "email", FusionMember::name, (final FusionMember m, final String n) ->
        new FusionMember(n, m.email(), m.age())
      )
    );
    assertTrue(e.getMessage().contains("reads 'name', not 'email'"), e.getMessage());
  }

  @Test
  @DisplayName("componentLens refuses a getter declared outside the receiver's type hierarchy")
  void componentLensRefusesAForeignGetter() {
    final var e = assertThrows(IllegalArgumentException.class, () ->
      Telescope.componentLens(
        FusionCrew.class,
        "name",
        (final FusionCrew c) -> c.label(),
        (final FusionCrew c, final String v) -> c
      )
    );
    assertTrue(e.getMessage().contains("method reference"), e.getMessage());
    final var foreign = assertThrows(IllegalArgumentException.class, () ->
      Telescope.<FusionCrew, String>componentLens(
        FusionCrew.class,
        "name",
        widen(FusionMember::name),
        (final FusionCrew c, final String v) -> c
      )
    );
    assertTrue(foreign.getMessage().contains("is not declared on"), foreign.getMessage());
  }

  @SuppressWarnings("unchecked")
  private static Accessor<FusionCrew, String> widen(final Accessor<FusionMember, String> getter) {
    return (Accessor<FusionCrew, String>) (Accessor<?, ?>) getter;
  }

  @Test
  @DisplayName("a record level whose edits all came from componentLens rebuilds through their setters in an image")
  void componentLensLevelRebuildRoute() {
    final var setterCalls = new AtomicInteger();
    final var name = Telescope.componentLens(
      FusionMember.class,
      "name",
      FusionMember::name,
      (final FusionMember m, final String v) -> {
        setterCalls.incrementAndGet();
        return new FusionMember(v, m.email(), m.age());
      }
    );
    final var email = Telescope.componentLens(
      FusionMember.class,
      "email",
      FusionMember::email,
      (final FusionMember m, final String v) -> {
        setterCalls.incrementAndGet();
        return new FusionMember(m.name(), v, m.age());
      }
    );
    final var root = Telescope.of(FusionMember.class);
    final var edits = List.<Edit<FusionMember>>of(
      over(root.then(name), String::toUpperCase),
      over(root.then(email), String::toLowerCase)
    );
    final var out = Telescope.all(edits.get(0), edits.get(1)).apply(new FusionMember("Ann", "ANN@X.IO", 30));

    assertEquals(new FusionMember("ANN", "ann@x.io", 30), out);
    // Off an image the level takes one positional rebuild through the canonical constructor and
    // never calls the setters; inside one it calls each edit's own setter once.
    assertEquals(NativeImage.IN_IMAGE ? 2 : 0, setterCalls.get());
  }

  @Test
  @DisplayName(
    "a componentLens setter that writes another component makes the fused result differ, as its contract warns"
  )
  void componentLensWithAMultiComponentSetterMakesFusionDiverge() {
    final var lying = Telescope.of(FusionMember.class).then(
      Telescope.componentLens(FusionMember.class, "name", FusionMember::name, (final FusionMember m, final String n) ->
        new FusionMember(n, n + "@lie", m.age())
      )
    );
    final var email = over(Telescope.of(FusionMember.class).field(FusionMember::email), String::toLowerCase);
    final var name = over(lying, (final String n) -> n + "1");
    assertTrue(fuses(email, name));
    final var src = new FusionMember("Ann", "ANN@X.IO", 30);
    final var seq = sequential(src, email, name);
    final var fused = Telescope.all(email, name).apply(src);
    assertEquals("Ann1@lie", seq.email());
    assertEquals("ann@x.io", fused.email());
    assertNotEquals(seq, fused);

    final var plainName = over(Telescope.of(FusionMember.class).field(FusionMember::name), (final String n) -> n + "0");
    assertTrue(fuses(plainName, name));
    assertNotEquals(sequential(src, plainName, name), Telescope.all(plainName, name).apply(src));
  }
}
