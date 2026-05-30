package org.telescope.codegen.lombok;

import java.util.HashSet;
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
 * <p>Annotation-processing order: Lombok injects the synthesised getters / setters / builder during
 * the same compilation, so this processor sees them via {@link
 * javax.lang.model.util.Elements#getAllMembers}. Place both processors in the same {@code
 * annotationProcessor} configuration; Lombok runs first by convention.
 */
@SupportedAnnotationTypes({ "lombok.Data", "lombok.Value", "lombok.Builder" })
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class LombokFocusProcessor extends AbstractTelescopeProcessor {

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var elements = processingEnv.getElementUtils();
    final var alreadyEmitted = new HashSet<String>();
    for (final var triggerFqn : LOMBOK_BEAN_ANNOTATIONS) {
      final var anno = elements.getTypeElement(triggerFqn);
      if (anno == null) continue;
      for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
        if (element.getKind() != ElementKind.CLASS) continue;
        if (element.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
          error(element, "telescope-lombok: only top-level classes are supported");
          continue;
        }
        final var pojo = (TypeElement) element;
        if (alreadyEmitted.add(pojo.getQualifiedName().toString())) {
          emitBeanNavigator(pojo, "@Data/@Value/@Builder", LOMBOK_BEAN_ANNOTATIONS);
        }
      }
    }
    return false;
  }
}
