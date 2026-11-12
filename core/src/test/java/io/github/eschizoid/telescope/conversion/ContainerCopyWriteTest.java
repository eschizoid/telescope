package io.github.eschizoid.telescope.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How {@link ContainerCopy} writes a container into a target that already holds one, the decision
 * both {@code Mapper.into} and a generated {@code into} hand it: refilled where it can change,
 * answered as the setter's to write where it cannot, refused where nothing can write it, and the
 * registered {@link ContainerCopy.Policy} consulted for every copy and refill.
 */
class ContainerCopyWriteTest {

  /** A target that records each element its adder is handed. */
  public static final class Owner {

    public final List<Object> added = new ArrayList<>();
  }

  private static final BiConsumer<Owner, Object> ADD = (owner, element) -> owner.added.add(element);

  /** A list that clears and takes no element back, the window a refill with no setter can leave. */
  private static final class ClearOnly extends ArrayList<String> {

    private static final long serialVersionUID = 1L;

    ClearOnly(final List<String> items) {
      super(items);
    }

    @Override
    public boolean add(final String item) {
      throw new UnsupportedOperationException("add");
    }

    @Override
    public boolean addAll(final Collection<? extends String> items) {
      throw new UnsupportedOperationException("addAll");
    }
  }

  private static String unwritable(final Object owner, final String property, final Object held) {
    return PairingMessages.intoUnwritable(
      "S",
      "T",
      PairingMessages.className(owner.getClass()),
      property,
      held == null ? null : held.getClass().getName()
    );
  }

  @Nested
  @DisplayName("Refusing a property nothing can write, before anything is written")
  class RefuseUnwritable {

    @Test
    @DisplayName("no container and no setter, or an unmodifiable JDK one and no setter, is refused by name")
    void anUnwritablePropertyIsRefused() {
      final var none = assertThrows(IllegalArgumentException.class, () ->
        ContainerCopy.refuseUnwritable(null, List.of("x"), false, false, "S", "T", "Owner", "tags")
      );
      assertEquals(PairingMessages.intoUnwritable("S", "T", "Owner", "tags", null), none.getMessage());
      final var frozen = List.of("held");
      final var unmodifiable = assertThrows(IllegalArgumentException.class, () ->
        ContainerCopy.refuseUnwritable(frozen, List.of("x"), false, true, "S", "T", "Owner", "tags")
      );
      assertEquals(
        PairingMessages.intoUnwritable("S", "T", "Owner", "tags", frozen.getClass().getName()),
        unmodifiable.getMessage()
      );
    }

    @Test
    @DisplayName("a setter, a changeable container, or an adder for a target holding none lets the write go ahead")
    void aWritablePropertyPasses() {
      ContainerCopy.refuseUnwritable(List.of("held"), List.of("x"), true, false, "S", "T", "Owner", "tags");
      ContainerCopy.refuseUnwritable(new ArrayList<>(), List.of("x"), false, false, "S", "T", "Owner", "tags");
      ContainerCopy.refuseUnwritable(null, List.of("x"), false, true, "S", "T", "Owner", "tags");
    }
  }

  @Nested
  @DisplayName("Refilling a held container")
  class Refill {

    @Test
    @DisplayName("a changeable list or map is cleared and refilled, keeping its identity")
    void aChangeableContainerKeepsItsIdentity() {
      final var list = new ArrayList<>(List.of("stale"));
      assertTrue(ContainerCopy.refill("S", "T", new Owner(), "tags", list, List.of("a", "b"), true));
      assertEquals(List.of("a", "b"), list);
      final var map = new HashMap<>(Map.of("stale", 1));
      assertTrue(ContainerCopy.refill("S", "T", new Owner(), "counts", map, Map.of("k", 2), true));
      assertEquals(Map.of("k", 2), map);
    }

    @Test
    @DisplayName("the container handed across as its own value is left as it is")
    void theSameContainerIsLeftAlone() {
      final var list = new ArrayList<>(List.of("x"));
      assertTrue(ContainerCopy.refill("S", "T", new Owner(), "tags", list, list, false));
      assertEquals(List.of("x"), list);
    }

    @Test
    @DisplayName("no container, an unmodifiable JDK one, or a null value goes to the setter")
    void theSetterWritesWhatCannotBeRefilled() {
      final var frozen = List.of("held");
      assertFalse(ContainerCopy.refill("S", "T", new Owner(), "tags", null, List.of("x"), true));
      assertFalse(ContainerCopy.refill("S", "T", new Owner(), "tags", frozen, List.of("x"), true));
      final var held = new ArrayList<>(List.of("held"));
      assertFalse(ContainerCopy.refill("S", "T", new Owner(), "tags", held, null, true));
      assertEquals(List.of("held"), held);
    }

    @Test
    @DisplayName("a null value with no setter to write it empties the container held")
    void aNullValueWithNoSetterEmpties() {
      final var held = new ArrayList<>(List.of("held"));
      assertTrue(ContainerCopy.refill("S", "T", new Owner(), "tags", held, null, false));
      assertEquals(List.of(), held);
    }

    @Test
    @DisplayName("a container whose clear throws goes to the setter, untouched, where there is one")
    void aContainerThatCannotClearGoesToTheSetter() {
      final var fixed = Arrays.asList("held");
      assertFalse(ContainerCopy.refill("S", "T", new Owner(), "tags", fixed, List.of("x"), true));
      assertEquals(List.of("held"), fixed);
    }

    @Test
    @DisplayName("with no setter it is refused by name, the exception it threw kept as the cause")
    void aContainerThatCannotClearIsRefusedWithNoSetter() {
      final var owner = new Owner();
      final var fixed = Arrays.asList("held");
      final var thrown = assertThrows(IllegalArgumentException.class, () ->
        ContainerCopy.refill("S", "T", owner, "tags", fixed, List.of("x"), false)
      );
      assertEquals(unwritable(owner, "tags", fixed), thrown.getMessage());
      assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
      assertEquals(List.of("held"), fixed);
    }

    @Test
    @DisplayName("a container that clears and then takes no element back is left empty, the second refusal kept")
    void aContainerThatTakesNothingBackIsLeftEmpty() {
      final var clearOnly = new ClearOnly(List.of("held"));
      final var thrown = assertThrows(IllegalArgumentException.class, () ->
        ContainerCopy.refill("S", "T", new Owner(), "tags", clearOnly, List.of("x"), false)
      );
      assertEquals(List.of(), clearOnly);
      assertEquals(1, thrown.getCause().getSuppressed().length);
    }

    @Test
    @DisplayName("a sorted set handed a null gets its elements back and goes to the setter, or is refused without one")
    void aSortedSetHandedANullGetsItsElementsBack() {
      final var withNull = new ArrayList<>(Arrays.asList("x", null));
      final var replaced = new TreeSet<>(List.of("keep"));
      assertFalse(ContainerCopy.refill("S", "T", new Owner(), "codes", replaced, withNull, true));
      assertEquals(Set.of("keep"), replaced);
      final var owner = new Owner();
      final var refused = new TreeSet<>(List.of("keep"));
      final var thrown = assertThrows(IllegalArgumentException.class, () ->
        ContainerCopy.refill("S", "T", owner, "codes", refused, withNull, false)
      );
      assertEquals(unwritable(owner, "codes", refused), thrown.getMessage());
      assertInstanceOf(NullPointerException.class, thrown.getCause());
      assertEquals(Set.of("keep"), refused);
    }

    @Test
    @DisplayName("a map that cannot change is refused with no setter, and keeps its entries")
    void aMapThatCannotChange() {
      final var owner = new Owner();
      final var frozen = Map.of("k", 1);
      assertThrows(IllegalArgumentException.class, () ->
        ContainerCopy.refill("S", "T", owner, "counts", frozen, Map.of("j", 2), false)
      );
      assertEquals(Map.of("k", 1), frozen);
    }

    @Test
    @DisplayName("a map that clears and then refuses its new entries gets its old entries back, and is refused")
    void aMapThatRefusesItsNewEntriesGetsItsOldOnesBack() {
      final var map = new RefusesOnce(Map.of("k", 1));
      assertThrows(IllegalArgumentException.class, () ->
        ContainerCopy.refill("S", "T", new Owner(), "counts", map, Map.of("j", 2), false)
      );
      assertEquals(Map.of("k", 1), map);
    }

    @Test
    @DisplayName("a null value with no setter to write it empties the map held")
    void aNullValueEmptiesTheMap() {
      final var map = new HashMap<>(Map.of("k", 1));
      assertTrue(ContainerCopy.refill("S", "T", new Owner(), "counts", map, null, false));
      assertEquals(Map.of(), map);
    }
  }

  /** A map whose first {@code putAll} refuses, after the clear before it has already emptied it. */
  private static final class RefusesOnce extends HashMap<String, Integer> {

    private static final long serialVersionUID = 1L;
    private boolean refused;

    RefusesOnce(final Map<String, Integer> entries) {
      super(entries);
    }

    @Override
    public void putAll(final Map<? extends String, ? extends Integer> entries) {
      if (!refused) {
        refused = true;
        throw new UnsupportedOperationException("putAll");
      }
      super.putAll(entries);
    }
  }

  @Nested
  @DisplayName("Filling a collection through its adder")
  class Fill {

    @Test
    @DisplayName("the held collection is cleared, then each element goes through the adder")
    void theHeldCollectionIsClearedThenFilled() {
      final var owner = new Owner();
      final var held = new ArrayList<Object>(List.of("stale"));
      assertTrue(ContainerCopy.fill("S", "T", owner, "lines", held, List.of("a", "b"), ADD, false));
      assertEquals(List.of(), held);
      assertEquals(List.of("a", "b"), owner.added);
    }

    @Test
    @DisplayName("a target holding no collection is filled through the adder, and a null value adds nothing")
    void noHeldCollectionIsFilledThroughTheAdder() {
      final var owner = new Owner();
      assertTrue(ContainerCopy.fill("S", "T", owner, "lines", null, List.of("a"), ADD, false));
      assertTrue(ContainerCopy.fill("S", "T", owner, "lines", null, null, ADD, false));
      assertEquals(List.of("a"), owner.added);
    }

    @Test
    @DisplayName("the target's own collection handed across is read before it is cleared")
    void theOwnCollectionIsReadBeforeTheClear() {
      final var owner = new Owner();
      final var held = new ArrayList<Object>(List.of("a", "b"));
      assertTrue(ContainerCopy.fill("S", "T", owner, "lines", held, held, ADD, false));
      assertEquals(List.of("a", "b"), owner.added);
    }

    @Test
    @DisplayName("an unmodifiable JDK collection goes to the setter where there is one")
    void anUnmodifiableCollectionGoesToTheSetter() {
      assertFalse(ContainerCopy.fill("S", "T", new Owner(), "lines", List.of("held"), List.of("a"), ADD, true));
    }

    @Test
    @DisplayName("an adder that rejects an element has its exception thrown on, with the held elements put back")
    void aRejectingAdderIsThrownOn() {
      for (final var setter : List.of(true, false)) {
        final var held = new ArrayList<Object>(List.of("held"));
        final var rejection = new IllegalArgumentException("rejected b");
        final BiConsumer<Owner, Object> checking = (target, element) -> {
          if ("b".equals(element)) throw rejection;
          held.add(element);
        };
        final var thrown = assertThrows(IllegalArgumentException.class, () ->
          ContainerCopy.fill("S", "T", new Owner(), "lines", held, List.of("a", "b"), checking, setter)
        );
        assertSame(rejection, thrown, "setter " + setter);
        assertEquals(List.of("held"), held, "setter " + setter);
      }
    }

    @Test
    @DisplayName("a held collection whose own clear throws still goes to the setter where an adder fills it")
    void aContainerThatCannotClearStillGoesToTheSetter() {
      final var fixed = Arrays.<Object>asList("held");
      assertFalse(ContainerCopy.fill("S", "T", new Owner(), "lines", fixed, List.of("a"), ADD, true));
      assertEquals(List.of("held"), fixed);
    }
  }

  @Nested
  @DisplayName("Adder calls and the deferral into builds under")
  class Adders {

    @Test
    @DisplayName("with no deferral armed the adder runs at once")
    void addRunsTheAdder() {
      final var owner = new Owner();
      ContainerCopy.add(owner, "lines", List.of("a"), ADD);
      assertEquals(List.of("a"), owner.added);
    }

    @Test
    @DisplayName("a deferred build runs no adder of its class and hands back what each was to be handed")
    void aDeferredBuildHandsBackTheElements() {
      final var lines = List.of("a");
      final var deferred = ContainerCopy.deferAdders(Owner.class, () -> {
        final var built = new Owner();
        ContainerCopy.add(built, "lines", lines, ADD);
        return built;
      });
      assertEquals(List.of(), deferred.value().added);
      final List<String> handed = deferred.elements("lines");
      assertSame(lines, handed);
      assertNull(deferred.elements("codes"));
    }
  }

  @Nested
  @DisplayName("The standard policy")
  class StandardPolicy {

    @Test
    @DisplayName("a null container copies to null, and any other through the standard copy")
    void ofCopiesThroughTheStandardCopy() {
      assertNull(ContainerCopy.of(null, List.class));
      final var source = new ArrayList<>(List.of("a"));
      final var copy = ContainerCopy.of(source, List.class);
      assertEquals(source, copy);
      assertNotSame(source, copy);
    }

    @Test
    @DisplayName("a policy that overrides nothing copies as the standard copy does")
    void theDefaultCopyIsTheStandardCopy() {
      final var source = new CopyOnWriteArrayList<>(List.of("a"));
      final var copied = new ContainerCopy.Policy() {}.copy(source, List.class);
      assertEquals(CopyOnWriteArrayList.class, copied.getClass());
      assertEquals(source, copied);
      assertNotSame(source, copied);
    }
  }

  /** A policy that records each call it is handed and then does the standard thing. */
  public static final class RecordingPolicy implements ContainerCopy.Policy {

    public static final List<String> CALLS = new ArrayList<>();

    @Override
    public Object copy(final Object source, final Class<?> declared) {
      CALLS.add("copy " + declared.getSimpleName());
      return ContainerCopy.Policy.super.copy(source, declared);
    }

    @Override
    public void refill(final ContainerCopy.Refill refill) {
      CALLS.add(
        "refill " +
          refill.property() +
          " of " +
          refill.owner().getClass().getSimpleName() +
          " from " +
          refill.incoming() +
          " into " +
          refill.held() +
          (refill.throughAdder() ? " through the adder" : "")
      );
      if (refill.held() instanceof Map<?, ?>) {
        ContainerCopy.Policy.super.refill(refill);
        return;
      }
      // Hands each element over itself, through the adder or into the collection held, rather
      // than through the standard refill.
      if (refill.held() != null) ((Collection<?>) refill.held()).clear();
      for (final var element : (Collection<?>) refill.incoming()) refill.add(element);
    }
  }

  /** A second policy, which may not be registered beside the first. */
  public static final class SecondPolicy implements ContainerCopy.Policy {}

  /** A policy that does not apply in this runtime, so registering it registers nothing. */
  public static final class InactivePolicy implements ContainerCopy.Policy {

    @Override
    public Object copy(final Object source, final Class<?> declared) {
      throw new AssertionError("an inactive policy is never asked");
    }

    @Override
    public boolean active() {
      return false;
    }
  }

  /**
   * A loader that defines its own {@link ContainerCopy} and the test policies, and serves a {@code
   * META-INF/services} entry naming {@code policies}, so the class it defines loads them as the
   * policy registered.
   */
  private static URLClassLoader registering(final String... policies) throws IOException {
    final var dir = Files.createTempDirectory("container-policy");
    final var services = dir.resolve("META-INF/services/" + ContainerCopy.Policy.class.getName());
    Files.createDirectories(services.getParent());
    Files.writeString(services, String.join("\n", policies) + "\n");
    final var parent = ContainerCopyWriteTest.class.getClassLoader();
    return new URLClassLoader(new URL[] { dir.toUri().toURL() }, parent) {
      @Override
      protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        final var own =
          name.startsWith(ContainerCopy.class.getName()) ||
          name.startsWith(ContainerCopyWriteTest.class.getName() + "$RecordingPolicy") ||
          name.startsWith(ContainerCopyWriteTest.class.getName() + "$SecondPolicy") ||
          name.startsWith(ContainerCopyWriteTest.class.getName() + "$InactivePolicy");
        if (!own) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
          final var loaded = findLoadedClass(name);
          if (loaded != null) return loaded;
          final var resource = name.replace('.', '/') + ".class";
          try (final var in = parent.getResourceAsStream(resource)) {
            if (in == null) throw new ClassNotFoundException(name);
            final var bytes = in.readAllBytes();
            // Defined with the domain of the class it copies, so it keeps that class's location.
            final var domain = (
              name.startsWith(ContainerCopy.class.getName()) ? ContainerCopy.class : ContainerCopyWriteTest.class
            ).getProtectionDomain();
            return defineClass(name, bytes, 0, bytes.length, domain);
          } catch (final IOException e) {
            throw new ClassNotFoundException(name, e);
          }
        }
      }
    };
  }

  /** A list whose every read throws, as a provider's lazy collection does outside its session. */
  private static final class Unreadable extends AbstractList<String> {

    @Override
    public String get(final int index) {
      throw new IllegalStateException("cannot be read");
    }

    @Override
    public int size() {
      throw new IllegalStateException("cannot be read");
    }

    @Override
    public Iterator<String> iterator() {
      throw new IllegalStateException("cannot be read");
    }

    @Override
    public Object[] toArray() {
      throw new IllegalStateException("cannot be read");
    }
  }

  @Nested
  @DisplayName("patched, the container patch gives a bean property the partial supplies")
  class Patched {

    @Test
    @DisplayName("is the held list, cleared and refilled with the incoming elements")
    void refillsTheHeldList() {
      final var held = new ArrayList<>(List.of("stale"));
      final var patched = ContainerCopy.patched(new Owner(), "tags", held, List.of("x", "y"));
      assertSame(held, patched);
      assertEquals(List.of("x", "y"), held);
    }

    @Test
    @DisplayName("is the held map, cleared and refilled with the incoming entries")
    void refillsTheHeldMap() {
      final var held = new HashMap<>(Map.of("stale", 0));
      final var patched = ContainerCopy.patched(new Owner(), "counts", held, Map.of("k", 1));
      assertSame(held, patched);
      assertEquals(Map.of("k", 1), held);
    }

    @Test
    @DisplayName("is the incoming container where the owner holds none, or the incoming one is null")
    void isTheIncomingOneWhereNothingIsHeld() {
      final var incoming = List.of("x");
      assertSame(incoming, ContainerCopy.patched(new Owner(), "tags", null, incoming));
      final var held = new ArrayList<>(List.of("stale"));
      assertNull(ContainerCopy.patched(new Owner(), "tags", held, null));
      assertEquals(List.of("stale"), held);
    }

    @Test
    @DisplayName("is the held container itself, untouched, where the incoming one is that same instance")
    void leavesTheSameInstanceAlone() {
      final var held = new ArrayList<>(List.of("a"));
      assertSame(held, ContainerCopy.patched(new Owner(), "tags", held, held));
      assertEquals(List.of("a"), held);
    }

    @Test
    @DisplayName("is the incoming container where the held one is an unmodifiable JDK container")
    void passesOverAnUnmodifiableHeldContainer() {
      final var held = List.of("stale");
      final var incoming = new ArrayList<>(List.of("x"));
      assertSame(incoming, ContainerCopy.patched(new Owner(), "tags", held, incoming));
    }

    @Test
    @DisplayName("is the incoming container where the held one refuses the change, and the held one keeps its elements")
    void passesOverAHeldContainerThatRefuses() {
      final var held = new TreeSet<>(Set.of("stale"));
      final var incoming = new ArrayList<String>();
      incoming.add(null);
      assertSame(incoming, ContainerCopy.patched(new Owner(), "codes", held, incoming));
      assertEquals(Set.of("stale"), held);
    }

    @Test
    @DisplayName("is the incoming container where the held one cannot even be read")
    void passesOverAHeldContainerThatCannotBeRead() {
      final var incoming = List.of("x");
      assertSame(incoming, ContainerCopy.patched(new Owner(), "tags", new Unreadable(), incoming));
    }

    @Test
    @DisplayName("is the incoming value where the held value is no container")
    void passesOverAHeldValueThatIsNoContainer() {
      final Object incoming = List.of("x");
      assertSame(incoming, ContainerCopy.patched(new Owner(), "tags", (Object) "not a container", incoming));
    }
  }

  @Nested
  @DisplayName("A registered policy")
  class RegisteredPolicy {

    @Test
    @DisplayName("is handed every copy and every refill, with the owner, the property and both containers")
    @SuppressWarnings("unchecked")
    void isHandedEveryCopyAndRefill() throws Exception {
      try (final var loader = registering(RecordingPolicy.class.getName())) {
        final var copy = loader.loadClass(ContainerCopy.class.getName());
        assertNotSame(ContainerCopy.class, copy);
        final var calls = (List<String>) loader.loadClass(RecordingPolicy.class.getName()).getField("CALLS").get(null);
        final var source = new ArrayList<>(List.of("a"));
        final var copied = copy.getMethod("of", Object.class, Class.class).invoke(null, source, List.class);
        assertEquals(source, copied);
        final var held = new ArrayList<>(List.of("stale"));
        final var refill = copy.getMethod(
          "refill",
          String.class,
          String.class,
          Object.class,
          String.class,
          Object.class,
          Object.class,
          boolean.class
        );
        assertEquals(true, refill.invoke(null, "S", "T", new Owner(), "tags", held, List.of("x"), true));
        assertEquals(List.of("x"), held);
        final var owner = new Owner();
        final var fill = copy.getMethod(
          "fill",
          String.class,
          String.class,
          Object.class,
          String.class,
          Object.class,
          Collection.class,
          BiConsumer.class,
          boolean.class
        );
        assertEquals(true, fill.invoke(null, "S", "T", owner, "lines", null, List.of("y"), ADD, false));
        assertEquals(List.of("y"), owner.added);
        final var counts = new HashMap<>(Map.of("stale", 0));
        assertEquals(true, refill.invoke(null, "S", "T", new Owner(), "counts", counts, Map.of("k", 1), true));
        assertEquals(Map.of("k", 1), counts);
        assertEquals(
          List.of(
            "copy List",
            "refill tags of Owner from [x] into [stale]",
            "refill lines of Owner from [y] into null through the adder",
            "refill counts of Owner from {k=1} into {stale=0}"
          ),
          calls
        );
      }
    }

    @Test
    @DisplayName("is passed over when it is not active, so another active one is the only one and is asked")
    @SuppressWarnings("unchecked")
    void anInactivePolicyIsPassedOver() throws Exception {
      try (final var loader = registering(InactivePolicy.class.getName(), RecordingPolicy.class.getName())) {
        final var copy = loader.loadClass(ContainerCopy.class.getName());
        final var calls = (List<String>) loader.loadClass(RecordingPolicy.class.getName()).getField("CALLS").get(null);
        final var source = new ArrayList<>(List.of("a"));
        assertEquals(source, copy.getMethod("of", Object.class, Class.class).invoke(null, source, List.class));
        assertEquals(List.of("copy List"), calls);
      }
    }

    @Test
    @DisplayName("is the standard one when the only one registered is not active")
    void onlyAnInactivePolicyLeavesTheStandardOne() throws Exception {
      try (final var loader = registering(InactivePolicy.class.getName())) {
        final var copy = loader.loadClass(ContainerCopy.class.getName());
        final var source = new ArrayList<>(List.of("a"));
        final var copied = copy.getMethod("of", Object.class, Class.class).invoke(null, source, List.class);
        assertEquals(source, copied);
        assertNotSame(source, copied);
      }
    }

    @Test
    @DisplayName("is the only one: two registered refuse the first container written, naming both")
    void twoRegisteredAreRefused() throws Exception {
      try (final var loader = registering(RecordingPolicy.class.getName(), SecondPolicy.class.getName())) {
        final var copy = loader.loadClass(ContainerCopy.class.getName());
        final var of = copy.getMethod("of", Object.class, Class.class);
        // Initialising the class is what fails, which a reflective call reports as it is.
        final var thrown = assertThrows(ExceptionInInitializerError.class, () ->
          of.invoke(null, List.of(), List.class)
        );
        final var refusal = thrown.getCause();
        assertEquals(
          "ContainerCopy: more than one active ContainerCopy.Policy is registered ([" +
            RecordingPolicy.class.getName() +
            ", " +
            SecondPolicy.class.getName() +
            "]); register at most one that is active.",
          refusal.getMessage()
        );
      }
    }
  }
}
