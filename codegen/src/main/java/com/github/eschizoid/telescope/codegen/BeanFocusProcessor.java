package com.github.eschizoid.telescope.codegen;

import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor for {@link com.github.eschizoid.telescope.annotations.BeanFocus} — the bean
 * analog of {@link FocusProcessor}. Discovers top-level annotated POJOs and dispatches to {@link
 * AbstractTelescopeProcessor#emitBeanNavigator}, which holds the shared bean-navigator emit
 * pipeline (used both here and from the {@code telescope-lombok} module's {@code
 * LombokFocusProcessor}).
 *
 * <p>For the generated shape — {@code <Pojo>Path<R>} plus one container step per collection
 * property, with reflection-free rebuild via static {@code builder()} or no-arg constructor +
 * setters — see {@link AbstractTelescopeProcessor#emitBeanNavigator}.
 */
@SupportedAnnotationTypes("com.github.eschizoid.telescope.annotations.BeanFocus")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class BeanFocusProcessor extends AbstractTelescopeProcessor {

  /**
   * Public no-arg constructor required by the {@link javax.annotation.processing.Processor} SPI.
   */
  public BeanFocusProcessor() {
    super();
  }

  private static final Set<String> TRIGGER = Set.of("com.github.eschizoid.telescope.annotations.BeanFocus");

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var anno = processingEnv
      .getElementUtils()
      .getTypeElement("com.github.eschizoid.telescope.annotations.BeanFocus");
    if (anno == null) return false;
    for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
      if (element.getKind() != ElementKind.CLASS) {
        error(element, "@BeanFocus is only supported on classes (records use @Focus)");
        continue;
      }
      if (element.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
        error(element, "@BeanFocus is only supported on top-level classes");
        continue;
      }
      emitBeanNavigator((TypeElement) element, "@BeanFocus", TRIGGER);
    }
    return true;
  }
}
