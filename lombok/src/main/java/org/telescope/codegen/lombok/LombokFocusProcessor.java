package org.telescope.codegen.lombok;

import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import org.telescope.codegen.AbstractTelescopeProcessor;

/**
 * Annotation processor that emits {@code <Pojo>Path<R>} navigators for classes carrying any of
 * {@code @lombok.Data} / {@code @lombok.Value} / {@code @lombok.Builder}. The class itself never
 * depends on Lombok at compile time — annotation triggers are looked up by string FQN, and the
 * processor is a graceful no-op when Lombok isn't on the consumer's processor path.
 *
 * <p>The emit pipeline is the same one used by {@link org.telescope.codegen.BeanFocusProcessor}:
 * scalar properties yield terminal {@code Telescope<R, T>} methods; container properties
 * (List/Set/Iterable, Map values, Optional) yield container steps with the matching {@code each} /
 * {@code eachValue} / {@code whenPresent} method; sub-properties whose class also carries a Lombok
 * bean annotation descend into their own generated Path.
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

  private final Set<TypeElement> pending = new LinkedHashSet<>();

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var elements = processingEnv.getElementUtils();
    for (final var triggerFqn : LOMBOK_BEAN_ANNOTATIONS) {
      final var anno = elements.getTypeElement(triggerFqn);
      if (anno == null) continue;
      for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
        if (element.getKind() != ElementKind.CLASS) continue;
        if (element.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
          error(element, "telescope-lombok: only top-level classes are supported");
          continue;
        }
        pending.add((TypeElement) element);
      }
    }
    if (roundEnv.processingOver()) {
      for (final var pojo : pending) {
        emitBeanNavigator(pojo, "@Data/@Value/@Builder", LOMBOK_BEAN_ANNOTATIONS);
      }
      pending.clear();
    }
    return false;
  }
}
