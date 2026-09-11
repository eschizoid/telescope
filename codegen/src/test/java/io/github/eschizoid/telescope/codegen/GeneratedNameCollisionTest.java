package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
  @DisplayName("a carrier-declared sub-pair held back for Lombok is still referenced by the carrier's name")
  void deferredCarrierSubPairIsReferencedByTheCarrierBridge() {
    // A pair whose types carry a Lombok trigger is held back until the final round, while the
    // parent's reference is written when the parent is planned. The trigger is matched by
    // annotation name, so a stub lombok.Data drives the real deferral without Lombok on the
    // classpath — and the POJOs carry explicit accessors, so nothing here reads a synthesised
    // member, which is the part an in-memory compilation genuinely cannot reproduce.
    final var compilation = compile(
      new BridgeProcessor(),
      source(
        "lombok.Data",
        """
        package lombok;
        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;
        @Retention(RetentionPolicy.SOURCE)
        @Target(ElementType.TYPE)
        public @interface Data {}
        """
      ),
      source(
        "q.Item",
        """
        package q;
        @lombok.Data
        public class Item {
          private String id;
          public Item() {}
          public String getId() { return id; }
          public void setId(final String v) { this.id = v; }
        }
        """
      ),
      source(
        "q.ItemDto",
        """
        package q;
        @lombok.Data
        public class ItemDto {
          private String id;
          public ItemDto() {}
          public String getId() { return id; }
          public void setId(final String v) { this.id = v; }
        }
        """
      ),
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
      () -> "a deferred carrier's pair must still resolve; saw " + compilation.errorMessages()
    );
    final var bridge = compilation.generated().get("p.RootBridge");
    assertTrue(
      bridge != null && bridge.contains("carrier.ItemCarrierBridge"),
      () -> "the parent must call the carrier's bridge, not a source-anchored name; saw " + bridge
    );
  }

  @Test
  @DisplayName("a multi-target source is referenced by the long name it is actually emitted under")
  void multiTargetSubBridgeIsReferencedByItsEmittedName() {
    // A source with two @Bridge targets cannot use the short name for either, because the short
    // name can belong to only one pair. The parent has to call whichever name was written.
    final var compilation = compile(
      new BridgeProcessor(),
      source(
        "demo.A",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.B.class)
        @Bridge(demo.C.class)
        public record A(String v) {}
        """
      ),
      source("demo.B", "package demo; public record B(String v) {}"),
      source("demo.C", "package demo; public record C(String v) {}"),
      source(
        "demo.P",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.PDto.class) public record P(demo.A a) {}
        """
      ),
      source("demo.PDto", "package demo; public record PDto(demo.B a) {}")
    );

    assertTrue(
      compilation.success(),
      () -> "the parent must call a sub-bridge that exists; saw " + compilation.errorMessages()
    );
    final var parent = compilation.generated().get("demo.PBridge");
    assertTrue(
      parent != null && parent.contains("AToBBridge"),
      () -> "the multi-target source is emitted under its long name, so the call must use it; saw " + parent
    );
    assertTrue(compilation.generated().containsKey("demo.AToBBridge"), "the long-named sub-bridge is what is written");
  }

  @Test
  @DisplayName("per-field holder names differ under case folding, not only under comparison")
  void holderNamesSurviveCaseFolding() {
    // Each holder is emitted as a nested class, so it becomes a class file of its own. Two whose
    // names differ only in case overwrite each other on a case-insensitive filesystem while javac
    // reports success, so the names have to differ before the component name does. Property names
    // really can differ only in case: decapitalisation keeps a leading acronym, so getUrl and
    // getURL yield url and URL.
    //
    // The assertion is on the names rather than on two files existing, because the case-folding
    // behaviour belongs to the filesystem: on a case-sensitive one no two names can collide and a
    // file-counting test would hold no matter what was emitted. Distinct-after-folding is the
    // property that decides the outcome on every filesystem.
    final var compilation = compile(
      new FocusProcessor(),
      source(
        "demo.CaseRec",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus
        public record CaseRec(String x, String X) {}
        """
      )
    );

    assertTrue(compilation.success(), () -> "both components are legal Java: " + compilation.errorMessages());
    final var navigator = compilation.generated().get("demo.CaseRecTelescope");
    assertNotNull(navigator);
    final var holders = java.util.regex.Pattern.compile("private static final class (\\S+) \\{")
      .matcher(navigator)
      .results()
      .map(m -> m.group(1))
      .toList();
    assertEquals(2, holders.size(), () -> "one holder per component: " + holders);
    assertEquals(
      2,
      holders
        .stream()
        .map(h -> h.toLowerCase(java.util.Locale.ROOT))
        .distinct()
        .count(),
      () -> "these collapse to one class file on a case-insensitive filesystem: " + holders
    );
  }

  @Test
  @DisplayName("a holder steps aside when its name would be the navigator's own")
  void holderStepsAsideFromTheNavigatorName() {
    // A member class may not carry its enclosing class's simple name (JLS 8.1), and the navigator
    // for Optic_0_x is Optic_0_xTelescope — which is what the holder for a component named
    // xTelescope at index 0 would otherwise be called.
    final var compilation = compile(
      new FocusProcessor(),
      source(
        "demo.Optic_0_x",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus
        public record Optic_0_x(String xTelescope, int other) {}
        """
      )
    );

    assertTrue(
      compilation.success(),
      () -> "the holder must not take the navigator's own name: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a holder steps aside when its name would be the navigated type's own")
  void holderStepsAsideFromTheSourceTypeName() {
    // A holder is a member type, so its simple name shadows a top-level type of the same name
    // throughout the navigator — including inside sibling holders, which spell the navigated type
    // by simple name.
    final var compilation = compile(
      new FocusProcessor(),
      source(
        "demo.Optic_0_thing",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus
        public record Optic_0_thing(String thing, int other) {}
        """
      )
    );

    assertTrue(
      compilation.success(),
      () -> "the navigated type must stay reachable from every holder: " + compilation.errorMessages()
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
