package io.github.eschizoid.telescope.codegen.lombok;

import io.github.eschizoid.telescope.codegen.AbstractTelescopeProcessor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that emits {@code <Pojo>Path<R>} navigators for classes carrying any of
 * {@code @lombok.Data} / {@code @lombok.Value} / {@code @lombok.Builder}. The class itself never
 * depends on Lombok at compile time — annotation triggers are looked up by string FQN, and the
 * processor is a graceful no-op when Lombok isn't on the consumer's processor path.
 *
 * <p>The emit pipeline is the same one used by {@link
 * io.github.eschizoid.telescope.codegen.BeanFocusProcessor}: scalar properties yield terminal
 * {@code Telescope<R, T>} methods; container properties (List/Set/Iterable, Map values, Optional)
 * yield container steps with the matching {@code each} / {@code eachValue} / {@code whenPresent}
 * method; sub-properties whose class also carries a Lombok bean annotation descend into their own
 * generated Path.
 *
 * <p><b>Round-deferred emission.</b> Lombok installs lazy AST visitors during processor init that
 * patch class declarations on traversal. In a non-trivial annotation-processor pipeline those
 * visitors haven't necessarily fired by the time round 1 starts, so a processor that queries {@link
 * javax.lang.model.util.Elements#getAllMembers} for a {@code @Data} class in round 1 may see the
 * un-patched member list (no getters / setters / builder). To stay correct regardless of round
 * ordering, this processor <em>collects</em> Lombok-annotated targets every round and only
 * <em>emits</em> when {@link RoundEnvironment#processingOver} is true — by then Lombok is
 * guaranteed done patching.
 */
@SupportedAnnotationTypes({ "lombok.Data", "lombok.Value", "lombok.Builder" })
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class LombokFocusProcessor extends AbstractTelescopeProcessor {

  /**
   * Public no-arg constructor required by the {@link javax.annotation.processing.Processor} SPI.
   */
  public LombokFocusProcessor() {
    super();
  }

  private final Set<TypeElement> pending = new LinkedHashSet<>();

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var elements = processingEnv.getElementUtils();
    for (final var triggerFqn : LOMBOK_BEAN_ANNOTATIONS) {
      final var anno = elements.getTypeElement(triggerFqn);
      if (anno == null) continue;
      for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
        if (element.getKind() != ElementKind.CLASS) continue;
        // Nested static classes are supported: emitBeanNavigator flattens the enclosing hierarchy
        // into the emitted Path / Step / <X>Telescope holder class names (e.g. an inner
        // `Outer.Inner` produces `OuterInnerPath`) and uses the dotted form for in-source type
        // references. Non-static inner classes (those whose enclosing element is a class but not
        // static) would still trip the no-no-arg-ctor or no-public-builder check in
        // emitBeanNavigator, so we don't have to reject them up-front.
        pending.add((TypeElement) element);
      }
    }
    // Emit on EVERY round that has fresh @Data/@Value/@Builder targets. We previously deferred to
    // `processingOver()` to ensure Lombok's lazy AST visitors had finished patching the host class
    // — but that delay meant the emitted Path symbol didn't exist when same-module main code was
    // resolved by the compiler. By emitting eagerly we make Path / Telescope / Step classes
    // visible in time for main-source binding; Lombok's patches resolve later when the *generated*
    // sources are themselves compiled, by which point Lombok has long finished. If Lombok hasn't
    // run yet in a given round we emit nothing useful and let a later round retry — the
    // `beanProperties()` query on an un-patched @Data returns empty, which `emitBeanNavigator`
    // already handles as a "no readable properties" no-op.
    for (final var pojo : List.copyOf(pending)) {
      if (emitBeanNavigatorIfReady(pojo)) pending.remove(pojo);
    }
    if (roundEnv.processingOver() && !pending.isEmpty()) {
      // Last-resort emit on processingOver(): any target whose host class never became readable
      // by the time annotation processing ends, still gets a navigator (the existing
      // "no readable properties" error from emitBeanNavigator surfaces the real problem then).
      for (final var pojo : pending) emitBeanNavigator(pojo, "@Data/@Value/@Builder", LOMBOK_BEAN_ANNOTATIONS);
      pending.clear();
    }
    return false;
  }

  /**
   * Emit the navigator only when {@code pojo}'s bean surface is actually readable in this round —
   * i.e. {@code beanProperties} returns a non-empty list. Returns {@code true} when emitted; the
   * caller drops the pojo from the pending set. Returns {@code false} when properties aren't yet
   * visible (typically: Lombok's AST patches haven't installed yet this round); the pojo stays in
   * pending for a retry in a later round.
   */
  private boolean emitBeanNavigatorIfReady(final TypeElement pojo) {
    if (beanProperties(pojo).isEmpty()) return false;
    emitBeanNavigator(pojo, "@Data/@Value/@Builder", LOMBOK_BEAN_ANNOTATIONS);
    return true;
  }
}
