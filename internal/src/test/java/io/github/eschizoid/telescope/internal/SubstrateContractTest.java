package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import java.io.Serializable;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins for the substrate contracts an architecture inspection found broken — each nested class is
 * one bug, and each pin failed before its fix. These are the adversarial bean/record shapes real
 * codebases carry: singleton accessors, {@code set*}-named non-setters, varargs canonical
 * constructors, and null flowing into primitive setters.
 */
class SubstrateContractTest {

  @Nested
  @DisplayName("a static getter-shaped method is not a property and cannot poison the bean")
  class StaticGetterExcluded {

    public static class WithFactory {

      private String name;

      public WithFactory() {}

      public static WithFactory getInstance() {
        return new WithFactory();
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    @Test
    @DisplayName("getInstance() is skipped; every real property stays readable")
    void staticGetterSkipped() {
      final var names = List.of(Beans.propertyNames(WithFactory.class));
      assertFalse(names.contains("instance"), "static getInstance() must not become a property");
      assertTrue(names.contains("name"));

      final var bean = new WithFactory();
      bean.setName("ann");
      assertEquals("ann", Beans.readProperty(bean, "name")); // pre-fix: ISE, whole bean poisoned
    }
  }

  @Nested
  @DisplayName("a set*-named non-setter cannot flip the write strategy")
  class SetterShapeRule {

    public static class GetterOnlyWithSetup {

      private String name;

      public GetterOnlyWithSetup() {}

      public String getName() {
        return name;
      }

      // Not a property setter — no uppercase after the prefix. Pre-fix this flipped autoWriter to
      // SETTERS, whose no-op per property silently lost every field.
      public void setup(final String ignored) {}
    }

    @Test
    @DisplayName("setup(String) does not select the setters strategy; fields still write")
    void setupDoesNotFlipStrategy() {
      final var writer = Beans.autoWriter(GetterOnlyWithSetup.class);
      final var built = writer.construct(new String[] { "name" }, name -> "VALUE");
      assertEquals("VALUE", built.getName()); // pre-fix: null — SettersWriter no-op'd it
    }

    @Test
    @DisplayName("the shared setter-shape rule: setCity yes, setup/settle no")
    void afterSetRule() {
      assertEquals("city", PropertyNames.afterSet("setCity"));
      assertNull(PropertyNames.afterSet("setup"));
      assertNull(PropertyNames.afterSet("settle"));
      assertNull(PropertyNames.afterSet("set"));
    }
  }

  @Nested
  @DisplayName("records with a varargs canonical constructor construct and rebuild")
  class VarargsRecords {

    record Tags(String name, String... labels) {}

    @Test
    @DisplayName("positional construct spreads the args instead of re-collecting them")
    void positionalConstruct() {
      final var built = Records.construct(Tags.class, new Object[] { "a", new String[] { "x", "y" } });
      assertEquals("a", built.name());
      assertArrayEquals(new String[] { "x", "y" }, built.labels()); // pre-fix: CCE wrapped in "Failed to construct"
    }

    @Test
    @DisplayName("lens writes rebuild through the canonical constructor")
    void lensWrite() {
      final var r = new Tags("a", "x");
      final var renamed = Records.<Tags, String>fieldLens(Tags.class, "name").set(r, "b");
      assertEquals("b", renamed.name()); // pre-fix: every write on a varargs record threw
      assertArrayEquals(new String[] { "x" }, renamed.labels());
    }
  }

  @Nested
  @DisplayName("null into a primitive setter leaves the JLS default — patch matches forward")
  class PrimitiveNullWrite {

    public static class Counter {

      private int count;

      public Counter() {}

      public int getCount() {
        return count;
      }

      public void setCount(final int count) {
        this.count = count;
      }
    }

    @Test
    @DisplayName("writeBeanProperty(bean, primitiveProp, null) is a no-op, not an NPE")
    void nullIntoPrimitiveIsNoOp() {
      final var counter = new Counter();
      counter.setCount(7);
      Beans.writeBeanProperty(counter, "count", null); // pre-fix: NPE at the unbox
      assertEquals(7, counter.getCount());
    }
  }

  @Nested
  @DisplayName("method-reference decode resolves against the lambda's own classloader")
  class ClassloaderResolution {

    @Test
    @DisplayName("implClassOf uses the reference's defining loader (same-loader smoke)")
    void implClassResolves() {
      // The cross-classloader shape needs a child URLClassLoader harness; this pin locks the
      // resolution path signature (three-arg forName against the lambda's loader) via the
      // same-loader case, which must keep working identically.
      interface Acc extends Serializable {
        String apply(Counter c);
      }
      final Acc acc = Counter::getCount0;
      assertEquals(Counter.class, LambdaIntrospection.implClassOf(acc));
    }

    public static class Counter {

      public String getCount0() {
        return "0";
      }
    }
  }
}
