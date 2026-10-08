package io.github.eschizoid.telescope.codegen;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Annotation processor for {@link io.github.eschizoid.telescope.annotations.BeanFocus} — the bean
 * analog of {@link FocusProcessor}. Discovers top-level annotated POJOs and dispatches to {@link
 * AbstractTelescopeProcessor#emitBeanNavigator}, which holds the shared bean-navigator emit
 * pipeline (used both here and from the {@code telescope-lombok} module's {@code
 * LombokFocusProcessor}).
 *
 * <p>For the generated shape — {@code <Pojo>Telescope<R>} plus one container step per collection
 * property, with reflection-free rebuild via static {@code builder()} or no-arg constructor +
 * setters — see {@link AbstractTelescopeProcessor#emitBeanNavigator}.
 */
@SupportedAnnotationTypes("io.github.eschizoid.telescope.annotations.BeanFocus")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class BeanFocusProcessor extends AbstractTelescopeProcessor {

  /**
   * Public no-arg constructor required by the {@link javax.annotation.processing.Processor} SPI.
   */
  public BeanFocusProcessor() {
    super();
  }

  // Targets carrying a Lombok trigger are deferred to processingOver(). Whether Lombok has patched
  // a class by the first round depends on where it sits on the processor path, and a read of an
  // un-patched class misses the builder, constructor and accessors Lombok adds -- which decides the
  // rebuild strategy, not only whether the bean is readable. By the final round Lombok is done.
  private final Set<TypeElement> pending = new LinkedHashSet<>();

  // Targets held back one round because their navigator would name a class telescope-lombok's
  // processor may still take on, which decides whether that hop descends or ends.
  private final Set<TypeElement> held = new LinkedHashSet<>();

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var anno = processingEnv
      .getElementUtils()
      .getTypeElement("io.github.eschizoid.telescope.annotations.BeanFocus");
    if (anno == null) return false;
    enterRound(roundEnv);
    final var heldLastRound = List.copyOf(held);
    held.clear();
    for (final var pojo : heldLastRound) emitBeanNavigator(pojo, "@BeanFocus", navigableBeanAnnotations());
    final var over = roundEnv.processingOver();
    for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
      if (element.getKind() != ElementKind.CLASS) {
        error(element, "@BeanFocus is only supported on classes (records use @Focus)");
        continue;
      }
      if (element.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
        error(element, "@BeanFocus is only supported on top-level classes");
        continue;
      }
      final var pojo = (TypeElement) element;
      if (!over && carriesLombokTrigger(pojo)) pending.add(pojo);
      else if (!over && awaitsLombokTarget(propertyTypes(pojo))) held.add(pojo);
      else emitBeanNavigator(pojo, "@BeanFocus", navigableBeanAnnotations());
    }
    if (over) {
      for (final var pojo : pending) {
        // A class telescope-lombok's processor has taken as a target gets the same <X>Telescope and
        // <X>FieldOptics from it, from the same rebuild, as soon as the members Lombok adds are
        // visible, so main code in the same compilation can name the navigator. Writing them here
        // too would collide; any other class is written by nothing else.
        if (writtenByLombok(pojo)) continue;
        emitBeanNavigator(pojo, "@BeanFocus", navigableBeanAnnotations());
      }
      pending.clear();
    }
    return true;
  }

  private List<TypeMirror> propertyTypes(final TypeElement pojo) {
    return beanProperties(pojo).stream().map(Prop::type).toList();
  }
}
