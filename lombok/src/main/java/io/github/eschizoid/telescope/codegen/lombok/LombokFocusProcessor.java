package io.github.eschizoid.telescope.codegen.lombok;

import io.github.eschizoid.telescope.codegen.AbstractTelescopeProcessor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;

/**
 * Annotation processor that emits {@code <Pojo>Telescope<R>} navigators for classes carrying any of
 * {@code @lombok.Data} / {@code @lombok.Value} / {@code @lombok.Builder}. The class itself never
 * depends on Lombok at compile time — annotation triggers are looked up by string FQN, and the
 * processor is a graceful no-op when Lombok isn't on the consumer's processor path.
 *
 * <p>The emit pipeline is the same one used by {@link
 * io.github.eschizoid.telescope.codegen.BeanFocusProcessor}: scalar properties yield terminal
 * {@code Telescope<R, T>} methods; container properties (List/Set/Iterable, Map values, Optional)
 * yield container steps with the matching {@code each} / {@code eachValue} / {@code whenPresent}
 * method; sub-properties whose class also carries a Lombok bean annotation descend into their own
 * generated navigator.
 *
 * <p><b>Round ordering.</b> Lombok installs lazy AST visitors during processor init that patch
 * class declarations on traversal. In a non-trivial annotation-processor pipeline those visitors
 * haven't necessarily fired by the time round 1 starts, so a processor that queries {@link
 * javax.lang.model.util.Elements#getAllMembers} for a {@code @Data} class in round 1 may see the
 * un-patched member list (no getters / setters / builder).
 *
 * <p>This processor therefore collects targets every round and emits each one as soon as its bean
 * surface reads complete, retrying the rest on later rounds. Complete means more than readable:
 * hand-written getters read in round one, while the {@code builder()} or constructor Lombok adds
 * decides the rebuild strategy, so a target waits until every member its Lombok annotations add is
 * visible. A target still unreadable when processing ends is run through the emit path on {@link
 * RoundEnvironment#processingOver} anyway, where the empty property list surfaces as a <em>no
 * readable properties</em> diagnostic rather than as silence. Deferring every target to that final
 * round would be simpler and is wrong: a navigator emitted only then does not exist yet when
 * same-module main code is resolved.
 */
@SupportedAnnotationTypes({ "lombok.Data", "lombok.Value", "lombok.Builder" })
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class LombokFocusProcessor extends AbstractTelescopeProcessor {

  /**
   * Public no-arg constructor required by the {@link javax.annotation.processing.Processor} SPI.
   */
  public LombokFocusProcessor() {
    super();
  }

  private final Set<TypeElement> pending = new LinkedHashSet<>();

  @Override
  public synchronized void init(final ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    markLombokProcessorActive();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var elements = processingEnv.getElementUtils();
    for (final var triggerFqn : LOMBOK_BEAN_ANNOTATIONS) {
      final var anno = elements.getTypeElement(triggerFqn);
      if (anno == null) continue;
      for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
        if (element.getKind() != ElementKind.CLASS) continue;
        // Nested static classes are supported: emitBeanNavigator flattens the enclosing hierarchy
        // into the emitted <X>Telescope / <X><Cap>Step / <X>FieldOptics class names (e.g. an inner
        // `Outer.Inner` produces `OuterInnerTelescope`) and uses the dotted form for in-source type
        // references. Non-static inner classes (those whose enclosing element is a class but not
        // static) would still trip the no-no-arg-ctor or no-public-builder check in
        // emitBeanNavigator, so we don't have to reject them up-front.
        pending.add((TypeElement) element);
      }
    }
    // Emit on EVERY round that has fresh @Data/@Value/@Builder targets, so the navigator exists in
    // time for same-module main code to bind against it. Lombok's patches resolve later, when the
    // *generated* sources are themselves compiled, by which point Lombok has long finished. A
    // round where Lombok has not yet run emits nothing useful and a later round retries: the
    // `beanProperties()` query on an un-patched @Data returns empty, which
    // `emitBeanNavigatorIfReady` treats as not-ready.
    for (final var pojo : List.copyOf(pending)) {
      if (emitBeanNavigatorIfReady(pojo)) pending.remove(pojo);
    }
    if (roundEnv.processingOver() && !pending.isEmpty()) {
      // Last-resort pass on processingOver(): a target whose host class never became readable goes
      // through emitBeanNavigator anyway, which finds no properties and reports the "no readable
      // properties" error — so an unreadable target ends as a diagnostic rather than as silence.
      for (final var pojo : pending) emitBeanNavigator(pojo, "@Data/@Value/@Builder", navigableBeanAnnotations());
      pending.clear();
    }
    return false;
  }

  /**
   * Emit the navigator only when {@code pojo}'s bean surface is complete in this round: it has
   * readable properties, and every member its class-level Lombok annotations add is visible.
   * Returns {@code true} when emitted; the caller drops the pojo from the pending set. Returns
   * {@code false} while Lombok's patches have not landed, and the pojo stays pending for a later
   * round.
   */
  private boolean emitBeanNavigatorIfReady(final TypeElement pojo) {
    if (beanProperties(pojo).isEmpty() || !lombokMembersPresent(pojo)) return false;
    emitBeanNavigator(pojo, "@Data/@Value/@Builder", navigableBeanAnnotations());
    return true;
  }

  /**
   * Whether the members {@code pojo}'s class-level Lombok annotations add are visible: the static
   * {@code builder()} of {@code @Builder}; the constructor of {@code @AllArgsConstructor} and
   * {@code @Value}, taking every instance field not given a value where it is declared; the
   * constructor of {@code @RequiredArgsConstructor}, taking every such field that is final; and the
   * setter Lombok names for every non-final instance field of {@code @Data} and {@code @Setter}.
   * Each of these takes part in choosing the rebuild strategy, so a strategy chosen before they
   * appear differs from the one the runtime writer chooses.
   *
   * <p>Where a fact cannot be read — an initializer this environment cannot see, accessors renamed
   * by {@code @Accessors} — the check asks for less rather than more, so a target is never held
   * back longer than its readable properties alone would hold it.
   */
  private boolean lombokMembersPresent(final TypeElement pojo) {
    final var fields = ElementFilter.fieldsIn(pojo.getEnclosedElements())
      .stream()
      .filter(f -> !f.getModifiers().contains(Modifier.STATIC))
      .toList();
    if (hasAnnotation(pojo, "lombok.Builder") && staticBuilderMethod(pojo) == null) return false;
    final var unset = fields
      .stream()
      .filter(f -> !initialisedInSource(f).orElse(true))
      .toList();
    if (
      (hasAnnotation(pojo, "lombok.AllArgsConstructor") || hasAnnotation(pojo, "lombok.Value")) &&
      !unset.isEmpty() &&
      !declaresConstructorOfArity(pojo, unset.size())
    ) {
      return false;
    }
    final var unsetFinals = (int) unset
      .stream()
      .filter(f -> f.getModifiers().contains(Modifier.FINAL))
      .count();
    if (
      hasAnnotation(pojo, "lombok.RequiredArgsConstructor") &&
      unsetFinals > 0 &&
      !declaresConstructorOfArity(pojo, unsetFinals)
    ) {
      return false;
    }
    if ((hasAnnotation(pojo, "lombok.Data") || hasAnnotation(pojo, "lombok.Setter")) && !setterSuppressed(pojo)) {
      if (hasAnnotation(pojo, "lombok.experimental.Accessors")) return true;
      for (final var field : fields) {
        if (field.getModifiers().contains(Modifier.FINAL) || setterSuppressed(field)) continue;
        if (hasAnnotation(field, "lombok.experimental.Accessors")) continue;
        if (!declaresSetter(pojo, lombokSetterName(field))) return false;
      }
    }
    return true;
  }

  /**
   * The setter name Lombok gives {@code field}: {@code set} and the capitalised name, where a
   * primitive {@code boolean} named {@code isX} drops its {@code is} first, as Lombok's getter
   * does.
   */
  private static String lombokSetterName(final VariableElement field) {
    final var name = field.getSimpleName().toString();
    final var isBoolean = field.asType().getKind() == TypeKind.BOOLEAN;
    final var base =
      isBoolean && name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))
        ? name.substring(2)
        : name;
    return "set" + Character.toUpperCase(base.charAt(0)) + base.substring(1);
  }

  /** Whether {@code pojo} declares a single-argument method named {@code name}, at any access. */
  private static boolean declaresSetter(final TypeElement pojo, final String name) {
    for (final var method : ElementFilter.methodsIn(pojo.getEnclosedElements())) {
      if (method.getParameters().size() == 1 && method.getSimpleName().contentEquals(name)) return true;
    }
    return false;
  }

  /**
   * Whether {@code element} carries {@code @Setter(AccessLevel.NONE)}, which asks for no setter.
   */
  private boolean setterSuppressed(final Element element) {
    for (final var mirror : element.getAnnotationMirrors()) {
      if (!((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals("lombok.Setter")) {
        continue;
      }
      for (final var value : mirror.getElementValues().values()) {
        if (value.getValue().toString().endsWith("NONE")) return true;
      }
    }
    return false;
  }

  private static boolean declaresConstructorOfArity(final TypeElement pojo, final int arity) {
    for (final var ctor : ElementFilter.constructorsIn(pojo.getEnclosedElements())) {
      if (ctor.getParameters().size() == arity) return true;
    }
    return false;
  }
}
