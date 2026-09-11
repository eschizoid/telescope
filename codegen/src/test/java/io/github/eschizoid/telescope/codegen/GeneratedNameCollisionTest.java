package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Three shapes where a name chosen for a generated artifact clashes with something else, and the
 * clash used to reach the author only as a javac error inside a file they never wrote — or, for the
 * bridge case, as a Filer failure attributed to a file carrying no annotation at all.
 *
 * <p>Each case asserts two things: the targeted diagnostic is present, and the raw downstream error
 * is not. The second half is what pins the improvement — a processor that reported the cause and
 * emitted the broken artifact anyway would satisfy the first assertion alone.
 *
 * <p>Compilation attributes the generated sources, so an error inside them is observable; under
 * {@code -proc:only} javac never visits them and the negative assertions would hold vacuously.
 */
class GeneratedNameCollisionTest {

  private static Compilation compile(final Processor processor, final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(processor), List.of(), sources);
  }

  @Test
  @DisplayName("a component named after a navigator method is reported, and no navigator is emitted")
  void componentCollidingWithNavigatorMethodIsReported() {
    final var compilation = compile(
      new FocusProcessor(),
      source(
        "demo.F",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus public record F(String get, String of, String explain, String read) {}
        """
      )
    );

    assertFalse(compilation.success(), "a component named after a navigator method must not compile");
    assertTrue(
      compilation.hasError("collides with the generated navigator's own"),
      () -> "expected the collision named at the record; saw " + compilation.errorMessages()
    );
    assertFalse(
      compilation.hasError("is already defined in class"),
      () ->
        "the duplicate-method error inside the generated file must not be what the author sees;" +
        " saw " +
        compilation.errorMessages()
    );
    assertTrue(
      compilation.generated().isEmpty(),
      () -> "no artifact should be emitted for a rejected record; saw " + compilation.generated().keySet()
    );
    // `read` takes a source argument on the navigator, so it overloads rather than clashing and is
    // absent from the reserved set.
    assertFalse(compilation.errorMessages().contains("'read'"), compilation::errorMessages);
  }

  @Test
  @DisplayName("a component named after the bridge hop is reported, on records and on beans alike")
  void componentCollidingWithBridgeHopIsReported() {
    // The hop is named for its target, so this one cannot live in a fixed set — it is passed in
    // per type. A record and a bean carrying the same clash must behave the same way.
    final var record = compile(
      new FocusProcessor(),
      source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus @Bridge(demo.Tgt.class) public record Src(String id, String asTgt) {}
        """
      ),
      source("demo.Tgt", "package demo; public record Tgt(String id, String asTgt) {}")
    );

    assertFalse(record.success(), "a component named after the bridge hop must not compile");
    assertTrue(
      record.hasError("collides with the generated navigator's own asTgt() method"),
      () -> "expected the hop collision named at the record; saw " + record.errorMessages()
    );
    assertFalse(
      record.hasError("is already defined in class"),
      () ->
        "the duplicate-method error inside the generated file must not be what the author sees;" +
        " saw " +
        record.errorMessages()
    );

    final var bean = compile(
      new BeanFocusProcessor(),
      source(
        "demo.SrcBean",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @BeanFocus @Bridge(demo.TgtBean.class) public class SrcBean {
          private String id;
          private String asTgtBean;
          public SrcBean() {}
          public String getId() { return id; }
          public void setId(final String id) { this.id = id; }
          public String getAsTgtBean() { return asTgtBean; }
          public void setAsTgtBean(final String v) { this.asTgtBean = v; }
        }
        """
      ),
      source(
        "demo.TgtBean",
        """
        package demo;
        public class TgtBean {
          private String id;
          private String asTgtBean;
          public TgtBean() {}
          public String getId() { return id; }
          public void setId(final String id) { this.id = id; }
          public String getAsTgtBean() { return asTgtBean; }
          public void setAsTgtBean(final String v) { this.asTgtBean = v; }
        }
        """
      )
    );

    assertFalse(bean.success(), "a property named after the bridge hop must not compile");
    assertTrue(
      bean.hasError("collides with the generated navigator's own asTgtBean() method"),
      () -> "expected the hop collision named at the class; saw " + bean.errorMessages()
    );
    assertFalse(
      bean.hasError("is already defined in class"),
      () ->
        "the duplicate-method error inside the generated file must not be what the author sees;" +
        " saw " +
        bean.errorMessages()
    );
  }

  @Test
  @DisplayName("a generic POJO is rejected before either artifact is written")
  void genericPojoEmitsNeitherArtifact() {
    final var compilation = compile(
      new BeanFocusProcessor(),
      source(
        "demo.BoxBean",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        @BeanFocus public class BoxBean<T> {
          private T value;
          public BoxBean() {}
          public T getValue() { return value; }
          public void setValue(final T value) { this.value = value; }
        }
        """
      )
    );

    assertFalse(compilation.success(), "a generic @BeanFocus class must not compile");
    assertTrue(
      compilation.hasError("cannot emit metadata constant"),
      () -> "expected the un-emittable-property diagnostic; saw " + compilation.errorMessages()
    );
    assertFalse(
      compilation.hasError("cannot find symbol"),
      () -> "the navigator referencing the type variable must not be emitted; saw " + compilation.errorMessages()
    );
    assertTrue(
      compilation.generated().isEmpty(),
      () -> "the holder is rejected and the navigator must go with it; saw " + compilation.generated().keySet()
    );
  }

  @Test
  @DisplayName("a generic record is rejected before either artifact is written")
  void genericRecordEmitsNeitherArtifact() {
    final var compilation = compile(
      new FocusProcessor(),
      source(
        "demo.Box",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus public record Box<T>(T item, String tag) {}
        """
      )
    );

    assertFalse(compilation.success(), "a generic @Focus record must not compile");
    assertTrue(
      compilation.hasError("cannot emit metadata constant"),
      () -> "expected the un-emittable-component diagnostic; saw " + compilation.errorMessages()
    );
    assertFalse(
      compilation.hasError("cannot find symbol"),
      () -> "the navigator referencing the type variable must not be emitted; saw " + compilation.errorMessages()
    );
    assertTrue(
      compilation.generated().isEmpty(),
      () -> "the holder is rejected and the navigator must go with it; saw " + compilation.generated().keySet()
    );
  }

  @Test
  @DisplayName("sub-bridges sharing a simple name across packages are referenced without ambiguity")
  void subBridgesSharingASimpleNameAcrossPackagesResolve() {
    // a.User -> a.UserDto and b.User -> b.UserDto each derive UserToUserDtoBridge, in their own
    // packages. The FQNs differ, so this is not a name clash the emitter can refuse — the parent
    // has to reference both, and referencing them by simple name would leave one unresolvable and
    // hand the other's forward a value of the wrong type.
    final var compilation = compile(
      new BridgeProcessor(),
      source("a.User", "package a; public record User(String name) {}"),
      source("a.UserDto", "package a; public record UserDto(String name) {}"),
      source("b.User", "package b; public record User(String name) {}"),
      source("b.UserDto", "package b; public record UserDto(String name) {}"),
      source(
        "demo.Root",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.RootDto.class) public record Root(a.User user, b.User other) {}
        """
      ),
      source("demo.RootDto", "package demo; public record RootDto(a.UserDto user, b.UserDto other) {}")
    );

    assertTrue(
      compilation.success(),
      () -> "both sub-bridges must be referenced unambiguously; saw " + compilation.errorMessages()
    );
    final var bridge = compilation.generated().get("demo.RootBridge");
    assertTrue(
      bridge != null && bridge.contains("a.UserToUserDtoBridge") && bridge.contains("b.UserToUserDtoBridge"),
      () -> "each sub-bridge must be named by its own package; saw " + bridge
    );
  }

  @Test
  @DisplayName("a carrier-declared sub-bridge is referenced by its carrier's package and name")
  void carrierDeclaredSubBridgeIsReferencedByItsCarrier() {
    // A carrier-form pair is emitted as <Carrier>Bridge in the carrier's package, so a parent
    // reaching it by recursion must name both halves from the carrier rather than from the pair's
    // source — which is neither the class's package nor its name.
    final var compilation = compile(
      new BridgeProcessor(),
      source("q.Item", "package q; public record Item(String sku) {}"),
      source("q.ItemDto", "package q; public record ItemDto(String sku) {}"),
      source(
        "carrier.ItemCarrier",
        """
        package carrier;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(source = q.Item.class, target = q.ItemDto.class)
        public final class ItemCarrier {}
        """
      ),
      source(
        "p.Root",
        """
        package p;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(p.RootDto.class) public record Root(q.Item item) {}
        """
      ),
      source("p.RootDto", "package p; public record RootDto(q.ItemDto item) {}")
    );

    assertTrue(
      compilation.success(),
      () -> "the carrier's bridge must be referenced where it is emitted; saw " + compilation.errorMessages()
    );
    final var bridge = compilation.generated().get("p.RootBridge");
    assertTrue(
      bridge != null && bridge.contains("carrier.ItemCarrierBridge"),
      () -> "expected the carrier's package and name in the reference; saw " + bridge
    );
  }

  @Test
  @DisplayName("two pairs whose auto-derived bridge names collide are named at the declaration")
  void collidingAutoBridgeNamesAreReported() {
    // a.MoneyA -> b.MoneyB and a.MoneyA -> c.MoneyB both derive MoneyAToMoneyBBridge in package a,
    // because the name is built from the simple names alone.
    final var compilation = compile(
      new BridgeProcessor(),
      source("a.MoneyA", "package a; public record MoneyA(long cents) {}"),
      source("b.MoneyB", "package b; public record MoneyB(long cents) {}"),
      source("c.MoneyB", "package c; public record MoneyB(long cents) {}"),
      source(
        "a.Src1",
        """
        package a;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(a.Dst1.class) public record Src1(a.MoneyA amount) {}
        """
      ),
      source("a.Dst1", "package a; public record Dst1(b.MoneyB amount) {}"),
      source(
        "a.Src2",
        """
        package a;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(a.Dst2.class) public record Src2(a.MoneyA amount) {}
        """
      ),
      source("a.Dst2", "package a; public record Dst2(c.MoneyB amount) {}")
    );

    assertFalse(compilation.success(), "colliding auto-bridge names must not compile");
    assertTrue(
      compilation.hasError("claimed by two different type pairs") &&
        compilation.hasError("b.MoneyB") &&
        compilation.hasError("c.MoneyB"),
      () -> "expected both colliding pairs named in the diagnostic; saw " + compilation.errorMessages()
    );
    assertFalse(
      compilation.hasError("Attempt to recreate a file"),
      () -> "the Filer failure must not be what the author sees; saw " + compilation.errorMessages()
    );
  }
}
