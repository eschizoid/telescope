package io.github.eschizoid.telescope.codegen;

import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/**
 * Generates Spring components for structural mappers and reusable record paths.
 *
 * <p>It also reads {@code @Bridge} and {@code @Bridges}, to know the bridge a carrier class or a
 * {@code @Bridge} field declares, and claims no annotation, so the bridge processor still sees
 * them. javac hands a processor only the annotations no processor ahead of it has claimed, and the
 * bridge processor claims {@code @Bridge}; this processor is therefore listed ahead of it, or a
 * round holding only a carrier would never reach it and a mapper written in a later round would
 * miss that carrier's bridge.
 */
@SupportedAnnotationTypes(
  {
    "io.github.eschizoid.telescope.annotations.TelescopeMapper",
    "io.github.eschizoid.telescope.annotations.TelescopeTransformer",
    "io.github.eschizoid.telescope.annotations.Bridge",
    "io.github.eschizoid.telescope.annotations.Bridges",
  }
)
public final class TelescopeMapperProcessor extends AbstractTelescopeProcessor {

  private static final String MAPPER = "io.github.eschizoid.telescope.annotations.TelescopeMapper";
  private static final String TRANSFORM = "io.github.eschizoid.telescope.annotations.TelescopeTransformer";
  private static final String BRIDGE = "io.github.eschizoid.telescope.annotations.Bridge";
  private static final String BRIDGES = "io.github.eschizoid.telescope.annotations.Bridges";
  private static final String PATH = "io.github.eschizoid.telescope.inject.TelescopeTransformation";
  private static final String PROJECTION = "io.github.eschizoid.telescope.inject.TelescopeProjection";
  private static final String CUSTOMIZER = "io.github.eschizoid.telescope.inject.TelescopeCustomizer";
  private static final String SPRING_COMPONENT = "org.springframework.stereotype.Component";
  private static final String TELESCOPE = "io.github.eschizoid.telescope.Telescope";
  private static final String CORE_MAPPER = "io.github.eschizoid.telescope.conversion.Mapper";
  private static final String MAPPER_BUILDER = "io.github.eschizoid.telescope.conversion.MapperBuilder";
  private static final String TRANSFORMATION = "io.github.eschizoid.telescope.inject.Transformation";
  private static final String OBJECT_PROVIDER = "org.springframework.beans.factory.ObjectProvider";
  private static final String LIST = "java.util.List";
  private static final String ARRAY_LIST = "java.util.ArrayList";
  private static final String OBJECTS = "java.util.Objects";

  /** The java.lang types the generated class names by their simple names, through no import. */
  private static final Set<String> IMPLICIT = Set.of("Override", "IllegalStateException");

  /**
   * The bridge each carrier class and each {@code @Bridge} field declares, keyed by {@link
   * #pair(TypeElement, TypeElement)}, from every round so far. A carrier is an element of the round
   * that holds it only, and a mapper in a later round still has to find its bridge.
   */
  private final Map<String, String> carried = new LinkedHashMap<>();

  /** Public constructor for processor discovery. */
  public TelescopeMapperProcessor() {
    super();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment round) {
    final var elements = processingEnv.getElementUtils();
    // A mapper is generated only where Spring is, and only a mapper looks a bridge up.
    if (elements.getTypeElement(SPRING_COMPONENT) != null) recordCarried(round);
    final var mapper = elements.getTypeElement(MAPPER);
    if (mapper != null) for (final var element : round.getElementsAnnotatedWith(mapper)) generateMapper(element);
    final var transform = elements.getTypeElement(TRANSFORM);
    if (transform != null) for (final var element : round.getElementsAnnotatedWith(transform)) generatePath(element);
    return false;
  }

  /** Records the bridge each carrier class and {@code @Bridge} field of {@code round} declares. */
  private void recordCarried(final RoundEnvironment round) {
    final var elements = processingEnv.getElementUtils();
    final var bridge = elements.getTypeElement(BRIDGE);
    final var bridges = elements.getTypeElement(BRIDGES);
    if (bridge == null || bridges == null) return;
    for (final var carrier : round.getElementsAnnotatedWithAny(bridge, bridges)) {
      if (carrier instanceof VariableElement field && carrier.getKind() == ElementKind.FIELD) {
        recordFieldBridge(field);
        continue;
      }
      for (final var mirror : bridgeAnnotations(carrier)) {
        final var carriedSource = declaredType(mirror, "source");
        final var carriedTarget = declaredType(mirror, "target");
        if (carriedSource == null || carriedTarget == null) continue;
        final var pkg = elements.getPackageOf(carrier).getQualifiedName().toString();
        final var name = carrier.getSimpleName() + "Bridge";
        carried.putIfAbsent(
          pair((TypeElement) carriedSource.asElement(), (TypeElement) carriedTarget.asElement()),
          pkg.isEmpty() ? name : pkg + "." + name
        );
      }
    }
  }

  /** The key {@link #carried} holds the bridge for {@code source -> target} under. */
  private static String pair(final TypeElement source, final TypeElement target) {
    return source.getQualifiedName() + " -> " + target.getQualifiedName();
  }

  private void generateMapper(final Element element) {
    if (!springPresent(element, "@TelescopeMapper")) return;
    final var blueprint = interfaceType(element, "@TelescopeMapper");
    if (blueprint == null) return;
    final var config = annotation(blueprint, MAPPER);
    final var projection = projectionType(blueprint.asType());
    final var explicitFrom = declaredType(config, "from");
    final var explicitTo = declaredType(config, "to");
    if ((explicitFrom == null) != (explicitTo == null)) {
      error(blueprint, "@TelescopeMapper from and to must be supplied together");
      return;
    }
    final DeclaredType from;
    final DeclaredType to;
    if (projection != null) {
      if (
        projection.getTypeArguments().size() != 2 ||
        !(projection.getTypeArguments().get(0) instanceof DeclaredType source) ||
        !(projection.getTypeArguments().get(1) instanceof DeclaredType target)
      ) {
        error(blueprint, "@TelescopeMapper requires concrete TelescopeProjection<S, T> types");
        return;
      }
      if (explicitFrom != null && (!same(source, explicitFrom) || !same(target, explicitTo))) {
        error(blueprint, "@TelescopeMapper from/to must match TelescopeProjection<S, T>");
        return;
      }
      from = source;
      to = target;
    } else {
      if (explicitFrom == null) {
        error(blueprint, "@TelescopeMapper requires from/to or TelescopeProjection<S, T>");
        return;
      }
      from = explicitFrom;
      to = explicitTo;
    }
    // The generated class names each model in .class literals, where Java allows no type arguments.
    for (final var model : List.of(from, to)) {
      if (!((TypeElement) model.asElement()).getTypeParameters().isEmpty()) {
        error(blueprint, "@TelescopeMapper does not support generic model types: " + model);
        return;
      }
    }
    // Checked in this order so each message names the part that is wrong, not the part already met.
    final var projectionElement = processingEnv.getElementUtils().getTypeElement(PROJECTION);
    final var mappingMethods = new ArrayList<ExecutableElement>();
    final var extraMethods = new ArrayList<ExecutableElement>();
    for (final var m : abstractMethods(blueprint)) {
      if (m.getSimpleName().contentEquals("map")) mappingMethods.add(m);
      else if (projection == null || !isProjectionMethod(m, blueprint, projectionElement)) extraMethods.add(m);
    }
    if (mappingMethods.isEmpty()) {
      final var found = extraMethods
        .stream()
        .map(m -> m.getSimpleName().toString())
        .collect(Collectors.joining(", "));
      error(
        blueprint,
        "@TelescopeMapper requires a method named map accepting " +
          from +
          " and returning " +
          to +
          (found.isEmpty() ? "" : "; found " + found)
      );
      return;
    }
    if (!extraMethods.isEmpty()) {
      final var extra = extraMethods.getFirst();
      error(
        blueprint,
        "@TelescopeMapper cannot implement " +
          ((TypeElement) extra.getEnclosingElement()).getQualifiedName() +
          "." +
          extra.getSimpleName() +
          "(); only map may be abstract"
      );
      return;
    }
    final var mapSignature = signature(blueprint, mappingMethods.getFirst());
    if (
      mappingMethods.size() != 1 ||
      !mappingMethods.getFirst().getTypeParameters().isEmpty() ||
      mapSignature.getParameterTypes().size() != 1 ||
      !same(mapSignature.getParameterTypes().getFirst(), from) ||
      !same(mapSignature.getReturnType(), to)
    ) {
      error(blueprint, "@TelescopeMapper requires map to accept " + from + " and return " + to);
      return;
    }
    final var declaredTransformers = transformerTypes(config, blueprint, from);
    if (declaredTransformers == null) return;
    final var bridgeClass = bridgeFor(from, to);
    if (projection == null && bridgeClass == null) {
      error(blueprint, "@TelescopeMapper requires a generated @Bridge for " + from + " -> " + to);
      return;
    }
    // The bridge is never imported. A bridge for a Lombok-annotated source is created in the last
    // processing round, and a class created then can be named but not imported. In the mapper's own
    // package its simple name resolves with no import at all, as the navigators already rely on.
    // Anywhere else it is named in full.
    final var bridgeName =
      bridgeClass == null
        ? null
        : packageOf(bridgeClass).equals(packageOf(blueprint))
          ? bridgeClass.substring(bridgeClass.lastIndexOf('.') + 1)
          : bridgeClass;
    final var method = mappingMethods.getFirst();
    final var refs = new ArrayList<String>();
    final var customTranslate = overridesTranslate(blueprint, projection);
    final var useBridge = !customTranslate && bridgeClass != null;
    if (projection != null && bridgeClass == null && !customTranslate) {
      processingEnv
        .getMessager()
        .printMessage(
          Diagnostic.Kind.NOTE,
          blueprint.getSimpleName() +
            " maps through a runtime Mapper built from same-name matching: no generated @Bridge for " +
            from +
            " -> " +
            to +
            " is visible to this compilation. A @Bridge on a carrier class or a field compiled into" +
            " another module is not, since @Bridge is not kept in class files; declare the projection" +
            " where that @Bridge is compiled, or override translate to map through the runtime Mapper" +
            " on purpose.",
          blueprint
        );
    }
    if (!useBridge) {
      refs.add(TELESCOPE);
      if (projection != null) {
        refs.add(CORE_MAPPER);
        refs.add(MAPPER_BUILDER);
      }
    }
    if (projection != null || !declaredTransformers.isEmpty()) {
      refs.add(PATH);
      refs.add(LIST);
    }
    if (projection != null) {
      refs.add(PROJECTION);
      refs.add(CUSTOMIZER);
      refs.add(OBJECT_PROVIDER);
      refs.add(ARRAY_LIST);
      refs.add(OBJECTS);
    }
    final var allTypes = new ArrayList<TypeMirror>(List.of(from, to));
    allTypes.addAll(declaredTransformers);
    final var taken = new HashSet<String>();
    if (bridgeName != null && bridgeName.indexOf('.') < 0) taken.add(bridgeName);
    emit(blueprint, config, allTypes, refs, taken, (out, names) -> {
      final var self = blueprint.getSimpleName().toString();
      final var model = names.of(from);
      final var target = names.of(to);
      final var list = names.ref(LIST);
      final var focus = names.ref(PATH) + "<" + model + ", ?>";
      final var pair = "<" + model + ", " + target + ">";
      if (!useBridge) {
        if (projection != null) {
          out.println("  private final " + names.ref(CORE_MAPPER) + pair + " mapping = createMapping();");
        } else {
          out.println(
            "  private final " +
              names.ref(TELESCOPE) +
              pair +
              " mapping = " +
              names.ref(TELESCOPE) +
              ".map(" +
              model +
              ".class, " +
              target +
              ".class);"
          );
        }
      }
      if (projection != null || !declaredTransformers.isEmpty()) {
        out.println("  private final " + list + "<" + focus + "> transformers;");
      }
      if (projection != null) {
        // Open while the constructor runs customizers; null afterwards, which fixes the list.
        out.println("  private " + names.ref(ARRAY_LIST) + "<" + focus + "> pending;");
        out.print("  public " + self + "Impl(");
        for (var i = 0; i < declaredTransformers.size(); i++) {
          out.print("final " + names.of(declaredTransformers.get(i)) + " transformer" + i + ", ");
        }
        out.println(
          "final " + names.ref(OBJECT_PROVIDER) + "<" + names.ref(CUSTOMIZER) + "<" + self + ">> customizers) {"
        );
        out.print("    pending = new " + names.ref(ARRAY_LIST) + "<>(");
        if (!declaredTransformers.isEmpty()) {
          out.print(list + ".of(");
          for (var i = 0; i < declaredTransformers.size(); i++) {
            if (i != 0) out.print(", ");
            out.print("transformer" + i);
          }
          out.print(")");
        }
        out.println(");");
        out.println("    customizers.orderedStream().forEach(customizer -> customizer.customize(this));");
        out.println("    this.transformers = " + list + ".copyOf(pending);");
        out.println("    pending = null;");
        out.println("  }");
      } else if (!declaredTransformers.isEmpty()) {
        out.print("  public " + self + "Impl(");
        for (var i = 0; i < declaredTransformers.size(); i++) {
          if (i != 0) out.print(", ");
          out.print("final " + names.of(declaredTransformers.get(i)) + " transformer" + i);
        }
        out.println(") {");
        out.print("    this.transformers = " + list + ".of(");
        for (var i = 0; i < declaredTransformers.size(); i++) {
          if (i != 0) out.print(", ");
          out.print("transformer" + i);
        }
        out.println(");");
        out.println("  }");
      }
      final var mapped = useBridge ? bridgeName : "mapping";
      out.println("  @Override public " + target + " " + method.getSimpleName() + "(final " + model + " input) {");
      if (projection == null && declaredTransformers.isEmpty()) {
        out.println("    return " + mapped + ".forward(input);");
      } else {
        out.println("    if (input == null) return null;");
        out.println("    final " + list + "<" + focus + "> current = transformers;");
        if (projection != null) {
          // Customizers receive the bean before its transformer list is fixed.
          out.println(
            "    if (current == null) throw new IllegalStateException(\"" +
              self +
              " cannot map while it is being constructed; a TelescopeCustomizer may only register transformers\");"
          );
        }
        out.println("    if (current.isEmpty()) return " + mapped + ".forward(input);");
        out.println("    " + model + " value = input;");
        out.println("    for (int i = 0, n = current.size(); i < n; i++) value = current.get(i).apply(value);");
        out.println("    return " + mapped + ".forward(value);");
      }
      out.println("  }");
      if (projection != null) {
        // A generated bridge's patch is the sparse overlay a core Mapper's is: it keeps
        // every base field the partial leaves null.
        out.println(
          "  @Override public " +
            model +
            " backward(final " +
            target +
            " input) { return " +
            mapped +
            ".backward(input); }"
        );
        out.println(
          "  @Override public " +
            model +
            " patch(final " +
            model +
            " source, final " +
            target +
            " partial) { return " +
            mapped +
            ".patch(source, partial); }"
        );
      }
      if (projection != null && !useBridge) {
        out.println("  private " + names.ref(CORE_MAPPER) + pair + " createMapping() {");
        out.println(
          "    final " +
            names.ref(MAPPER_BUILDER) +
            pair +
            " builder = " +
            names.ref(TELESCOPE) +
            ".mapperBuilder(" +
            model +
            ".class, " +
            target +
            ".class);"
        );
        out.println("    " + self + ".super.translate(builder);");
        out.println("    return builder.build();");
        out.println("  }");
      }
      if (projection != null) {
        out.println(
          "  @Override public " + names.ref(PROJECTION) + pair + " addTransformer(final " + focus + " transformer) {"
        );
        out.println("    " + names.ref(OBJECTS) + ".requireNonNull(transformer, \"transformer must not be null\");");
        out.println(
          "    if (pending == null) throw new IllegalStateException(\"" +
            self +
            " transformers are fixed once the bean is constructed; register them from a TelescopeCustomizer<" +
            self +
            "> bean\");"
        );
        out.println("    pending.add(transformer);");
        out.println("    return this;");
        out.println("  }");
      }
    });
  }

  /**
   * The qualified name of the generated bridge for {@code from -> to}, or null when there is none.
   * The names follow {@code BridgeProcessor}: a carrier's bridge is {@code <Carrier>Bridge} in the
   * carrier's package; a field's is {@code <Source>To<Target>Bridge} in the package of the class
   * declaring it; a source declaring several targets gets {@code <Source>To<Target>Bridge}; a
   * source declaring one gets {@code <Source>Bridge}. A carrier or a field is read in the round
   * that holds it, which may be earlier than the mapper's. A bridge compiled into a dependency has
   * no annotation left to read, so the two source-anchored names are also probed as classes; a
   * carrier's or a field's bridge there is named after a class this processor cannot know.
   */
  private String bridgeFor(final DeclaredType from, final DeclaredType to) {
    final var elements = processingEnv.getElementUtils();
    final var source = (TypeElement) from.asElement();
    final var sourceBridges = bridgeAnnotations(source);
    for (final var bridge : sourceBridges) {
      final var target = declaredType(bridge, "value");
      if (target != null && same(target, to)) return sourceAnchoredName(source, to, sourceBridges.size() > 1);
    }
    final var declared = carried.get(pair(source, (TypeElement) to.asElement()));
    if (declared != null) return declared;
    for (final var multiTarget : List.of(false, true)) {
      final var name = sourceAnchoredName(source, to, multiTarget);
      if (hasForward(elements.getTypeElement(name), from, to)) return name;
    }
    return null;
  }

  /**
   * Records the bridge {@code field}, a {@code @Bridge} mapper field, declares for the pair its
   * mapper call names; records nothing on a compiler without the javac tree API, which cannot read
   * the call.
   */
  private void recordFieldBridge(final VariableElement field) {
    final Trees trees;
    try {
      trees = Trees.instance(processingEnv);
    } catch (final IllegalArgumentException e) {
      return;
    }
    final var rows = new FieldRows(processingEnv, trees);
    final var call = rows.initializerCall(field);
    final var path = trees.getPath(field);
    if (call == null || path == null || call.getArguments().size() < 2) return;
    final var unit = path.getCompilationUnit();
    // Resolved by name: attributing the call would resolve its accessor references too, which
    // reports an error for a getter Lombok has not written yet.
    final var owner = (TypeElement) field.getEnclosingElement();
    final var source = rows.classLiteralByName(unit, call.getArguments().get(0), owner);
    final var target = rows.classLiteralByName(unit, call.getArguments().get(1), owner);
    if (source == null || target == null) return;
    final var pkg = processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
    final var name = FieldRows.bridgeSimpleName(source, target);
    carried.putIfAbsent(pair(source, target), pkg.isEmpty() ? name : pkg + "." + name);
  }

  private String sourceAnchoredName(final TypeElement source, final DeclaredType to, final boolean multiTarget) {
    final var pkg = processingEnv.getElementUtils().getPackageOf(source).getQualifiedName().toString();
    final var name = multiTarget
      ? source.getSimpleName() + "To" + to.asElement().getSimpleName() + "Bridge"
      : source.getSimpleName() + "Bridge";
    return pkg.isEmpty() ? name : pkg + "." + name;
  }

  /**
   * Every {@code @Bridge} on {@code element}, whether written once or repeated under
   * {@code @Bridges}.
   */
  private static List<AnnotationMirror> bridgeAnnotations(final Element element) {
    final var result = new ArrayList<AnnotationMirror>();
    final var single = annotation(element, BRIDGE);
    if (single != null) result.add(single);
    if (value(annotation(element, BRIDGES), "value") instanceof List<?> repeated) {
      for (final var entry : repeated)
        if (entry instanceof AnnotationValue av && av.getValue() instanceof AnnotationMirror m) {
          result.add(m);
        }
    }
    return result;
  }

  private boolean hasForward(final TypeElement bridge, final DeclaredType from, final DeclaredType to) {
    return (
      bridge != null &&
      ElementFilter.methodsIn(bridge.getEnclosedElements())
        .stream()
        .anyMatch(
          m ->
            m.getSimpleName().contentEquals("forward") &&
            m.getModifiers().contains(Modifier.PUBLIC) &&
            m.getModifiers().contains(Modifier.STATIC) &&
            m.getParameters().size() == 1 &&
            same(m.getParameters().getFirst().asType(), from) &&
            same(m.getReturnType(), to)
        )
    );
  }

  /** Whether {@code m} is one of TelescopeProjection's own methods, or an override of one. */
  private boolean isProjectionMethod(
    final ExecutableElement m,
    final TypeElement blueprint,
    final TypeElement projection
  ) {
    final var elements = processingEnv.getElementUtils();
    return ElementFilter.methodsIn(projection.getEnclosedElements())
      .stream()
      .anyMatch(p -> p.equals(m) || elements.overrides(m, p, blueprint));
  }

  /**
   * Whether the blueprint's hierarchy overrides {@code TelescopeProjection.translate}. An override
   * on any parent interface counts; a method that is merely named {@code translate} does not, and a
   * plain {@code map} interface has nothing to override.
   */
  private boolean overridesTranslate(final TypeElement blueprint, final DeclaredType projection) {
    if (projection == null) return false;
    final var elements = processingEnv.getElementUtils();
    final var projectionType = elements.getTypeElement(PROJECTION);
    final var base = ElementFilter.methodsIn(projectionType.getEnclosedElements())
      .stream()
      .filter(m -> m.getSimpleName().contentEquals("translate"))
      .findFirst()
      .orElseThrow();
    return ElementFilter.methodsIn(elements.getAllMembers(blueprint))
      .stream()
      .anyMatch(m -> !m.getEnclosingElement().equals(projectionType) && elements.overrides(m, base, blueprint));
  }

  private DeclaredType projectionType(final TypeMirror type) {
    if (!(type instanceof DeclaredType declared)) return null;
    if (((TypeElement) declared.asElement()).getQualifiedName().contentEquals(PROJECTION)) return declared;
    for (final var parent : processingEnv.getTypeUtils().directSupertypes(type)) {
      final var found = projectionType(parent);
      if (found != null) return found;
    }
    return null;
  }

  private List<DeclaredType> transformerTypes(
    final AnnotationMirror config,
    final TypeElement blueprint,
    final TypeMirror from
  ) {
    final var raw = value(config, "transformers");
    if (raw == null) return List.of();
    if (!(raw instanceof List<?> entries)) {
      error(blueprint, "@TelescopeMapper transformers must be class literals");
      return null;
    }
    final var result = new ArrayList<DeclaredType>();
    for (final var entry : entries) {
      final var target = entry instanceof AnnotationValue av ? av.getValue() : null;
      if (!(target instanceof DeclaredType declared)) {
        error(blueprint, "@TelescopeMapper transformers must be class literals");
        return null;
      }
      final var transformation = transformationType(declared);
      if (
        transformation == null ||
        transformation.getTypeArguments().size() != 2 ||
        !same(transformation.getTypeArguments().get(0), from)
      ) {
        error(blueprint, "@TelescopeMapper transformer " + declared + " must focus on " + from);
        return null;
      }
      result.add(declared);
    }
    return result;
  }

  private void generatePath(final Element element) {
    if (!springPresent(element, "@TelescopeTransformer")) return;
    final var blueprint = interfaceType(element, "@TelescopeTransformer");
    if (blueprint == null) return;
    final var config = annotation(blueprint, TRANSFORM);
    final var declared = transformationType(blueprint.asType());
    if (declared == null || declared.getTypeArguments().size() != 2) {
      error(blueprint, "@TelescopeTransformer must extend TelescopeTransformation<S, A> with concrete types");
      return;
    }
    final var from = declared.getTypeArguments().get(0);
    final var to = declared.getTypeArguments().get(1);
    final var pathMethod = ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(blueprint))
      .stream()
      .filter(m -> m.getSimpleName().contentEquals("path") && m.getParameters().isEmpty())
      .findFirst()
      .orElse(null);
    if (pathMethod == null || !pathMethod.getModifiers().contains(Modifier.DEFAULT)) {
      error(blueprint, "@TelescopeTransformer requires a default path()");
      return;
    }
    final var transformMethod = ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(blueprint))
      .stream()
      .filter(m -> m.getSimpleName().contentEquals("transform") && m.getParameters().isEmpty())
      .findFirst()
      .orElse(null);
    if (transformMethod == null || !transformMethod.getModifiers().contains(Modifier.DEFAULT)) {
      error(blueprint, "@TelescopeTransformer requires a default transform() returning Transformation<A>");
      return;
    }
    final var types = processingEnv.getTypeUtils();
    final var expectedPath = types.getDeclaredType(
      processingEnv.getElementUtils().getTypeElement("io.github.eschizoid.telescope.Telescope"),
      from,
      to
    );
    final var expectedTransform = types.getDeclaredType(
      processingEnv.getElementUtils().getTypeElement("io.github.eschizoid.telescope.inject.Transformation"),
      to
    );
    if (
      !same(signature(blueprint, pathMethod).getReturnType(), expectedPath) ||
      !same(signature(blueprint, transformMethod).getReturnType(), expectedTransform)
    ) {
      error(
        blueprint,
        "@TelescopeTransformer requires typed Telescope<S, A> path() and Transformation<A> transform() results"
      );
      return;
    }
    if (!abstractMethods(blueprint).isEmpty()) {
      error(blueprint, "@TelescopeTransformer requires no other abstract methods");
      return;
    }
    emit(blueprint, config, List.of(from, to), List.of(TELESCOPE, TRANSFORMATION, OBJECTS), Set.of(), (out, names) -> {
      final var self = blueprint.getSimpleName().toString();
      final var objects = names.ref(OBJECTS);
      final var pathType = names.ref(TELESCOPE) + "<" + names.of(from) + ", " + names.of(to) + ">";
      out.println("  private final " + pathType + " path =");
      out.println("    " + objects + ".requireNonNull(" + self + ".super.path(), \"path() must not return null\");");
      out.println("  @Override public " + pathType + " path() {");
      out.println("    return path;");
      out.println("  }");
      final var transformationType = names.ref(TRANSFORMATION) + "<" + names.of(to) + ">";
      out.println("  private final " + transformationType + " transformation =");
      out.println(
        "    " + objects + ".requireNonNull(" + self + ".super.transform(), \"transform() must not return null\");"
      );
      out.println("  @Override public " + transformationType + " transform() {");
      out.println("    return transformation;");
      out.println("  }");
      out.println("  @Override public " + names.of(from) + " apply(final " + names.of(from) + " input) {");
      out.println("    return path.update(input, transformation);");
      out.println("  }");
    });
  }

  private DeclaredType transformationType(final TypeMirror type) {
    if (!(type instanceof DeclaredType declared)) return null;
    if (((TypeElement) declared.asElement()).getQualifiedName().contentEquals(PATH)) return declared;
    for (final var parent : processingEnv.getTypeUtils().directSupertypes(type)) {
      final var found = transformationType(parent);
      if (found != null) return found;
    }
    return null;
  }

  private ExecutableType signature(final TypeElement owner, final ExecutableElement method) {
    return (ExecutableType) processingEnv.getTypeUtils().asMemberOf((DeclaredType) owner.asType(), method);
  }

  /**
   * The annotations live in core, but what they generate is a Spring component. Without Spring on
   * the classpath, say so on the user's interface rather than emit a class that cannot compile.
   */
  private boolean springPresent(final Element element, final String annotation) {
    if (processingEnv.getElementUtils().getTypeElement(SPRING_COMPONENT) != null) return true;
    error(
      element,
      annotation +
        " generates a Spring component; add telescope-spring-boot-starter to the classpath of the module" +
        " that declares this interface"
    );
    return false;
  }

  private TypeElement interfaceType(final Element element, final String annotation) {
    if (!(element instanceof TypeElement type) || type.getKind() != ElementKind.INTERFACE) {
      error(element, annotation + " requires an interface");
      return null;
    }
    if (!type.getTypeParameters().isEmpty()) {
      error(element, annotation + " requires a non-generic interface");
      return null;
    }
    if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
      error(element, annotation + " requires a top-level interface");
      return null;
    }
    if (type.getModifiers().contains(Modifier.SEALED)) {
      error(element, annotation + " requires a non-sealed interface");
      return null;
    }
    return type;
  }

  private List<ExecutableElement> abstractMethods(final TypeElement type) {
    return ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(type))
      .stream()
      .filter(m -> m.getModifiers().contains(Modifier.ABSTRACT))
      .toList();
  }

  private boolean same(final TypeMirror left, final TypeMirror right) {
    return processingEnv.getTypeUtils().isSameType(left, right);
  }

  private static AnnotationMirror annotation(final Element element, final String name) {
    for (final var mirror : element.getAnnotationMirrors())
      if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(name)) return mirror;
    return null;
  }

  private static Object value(final AnnotationMirror mirror, final String key) {
    if (mirror == null) return null;
    for (final var entry : mirror.getElementValues().entrySet())
      if (entry.getKey().getSimpleName().contentEquals(key)) return entry.getValue().getValue();
    return null;
  }

  private static DeclaredType declaredType(final AnnotationMirror mirror, final String key) {
    final var value = value(mirror, key);
    return value instanceof DeclaredType dt ? dt : null;
  }

  /**
   * How a generated class writes each type it names. A type is imported and written by its simple
   * name unless that name is taken: by another type the class names, by the class itself or its
   * interface, by a java.lang type the class names without an import, or by a bridge the class
   * names by its simple name. A type whose simple name is taken is written in full and not
   * imported, since an import would hide the other type of that name, or clash with its import.
   */
  private static final class Names {

    private final Set<String> imported = new TreeSet<>();
    private final Set<String> inFull = new HashSet<>();

    Names(final Set<String> named, final Set<String> taken) {
      final var bySimpleName = new HashMap<String, Integer>();
      for (final var qualified : named) bySimpleName.merge(simpleName(qualified), 1, Integer::sum);
      for (final var qualified : named) {
        final var simple = simpleName(qualified);
        if (bySimpleName.get(simple) > 1 || taken.contains(simple)) inFull.add(qualified);
        else imported.add(qualified);
      }
    }

    /** The imports, sorted; a type of the default package has no name to import by. */
    List<String> imports() {
      return imported
        .stream()
        .filter(qualified -> qualified.indexOf('.') >= 0)
        .toList();
    }

    /** {@code qualified} as the class writes it. */
    String ref(final String qualified) {
      return inFull.contains(qualified) ? qualified : simpleName(qualified);
    }

    /** {@code type} as the class writes it, with its type arguments. */
    String of(final TypeMirror type) {
      if (type instanceof DeclaredType declared) {
        final var element = (TypeElement) declared.asElement();
        final var name =
          declared.getEnclosingType() instanceof DeclaredType enclosing &&
          !element.getModifiers().contains(Modifier.STATIC)
            ? of(enclosing) + "." + element.getSimpleName()
            : ref(element.getQualifiedName().toString());
        return declared.getTypeArguments().isEmpty()
          ? name
          : name + "<" + declared.getTypeArguments().stream().map(this::of).collect(Collectors.joining(", ")) + ">";
      }
      if (type instanceof ArrayType array) return of(array.getComponentType()) + "[]";
      if (type instanceof WildcardType wildcard) {
        if (wildcard.getExtendsBound() != null) return "? extends " + of(wildcard.getExtendsBound());
        if (wildcard.getSuperBound() != null) return "? super " + of(wildcard.getSuperBound());
        return "?";
      }
      return type.toString();
    }

    /** The simple name of {@code qualified}: of a nested type, its own name. */
    private static String simpleName(final String qualified) {
      return qualified.substring(qualified.lastIndexOf('.') + 1);
    }
  }

  /**
   * Collects the qualified names a class naming {@code type} has to reach: of a nested type that is
   * not static, its enclosing type's, through which it is written.
   */
  private static void collectImports(final TypeMirror type, final Set<String> imports) {
    if (type instanceof DeclaredType declared) {
      if (
        declared.getEnclosingType() instanceof DeclaredType enclosing &&
        !declared.asElement().getModifiers().contains(Modifier.STATIC)
      ) collectImports(enclosing, imports);
      else imports.add(((TypeElement) declared.asElement()).getQualifiedName().toString());
      for (final var argument : declared.getTypeArguments()) collectImports(argument, imports);
    } else if (type instanceof ArrayType array) collectImports(array.getComponentType(), imports);
    else if (type instanceof WildcardType wildcard) {
      if (wildcard.getExtendsBound() != null) collectImports(wildcard.getExtendsBound(), imports);
      if (wildcard.getSuperBound() != null) collectImports(wildcard.getSuperBound(), imports);
    }
  }

  /**
   * Writes {@code <Blueprint>Impl}, a Spring component implementing {@code blueprint}, beside it.
   * The class names {@code types} and the qualified names in {@code references}; {@code taken}
   * holds the simple names the body writes for something else, which no import may take.
   */
  private void emit(
    final TypeElement blueprint,
    final AnnotationMirror config,
    final List<? extends TypeMirror> types,
    final List<String> references,
    final Set<String> taken,
    final BiConsumer<PrintWriter, Names> body
  ) {
    final var pkg = processingEnv.getElementUtils().getPackageOf(blueprint).getQualifiedName().toString();
    final var name = blueprint.getSimpleName() + "Impl";
    final var qualified = pkg.isEmpty() ? name : pkg + "." + name;
    final var named = new HashSet<String>();
    named.add(SPRING_COMPONENT);
    named.addAll(references);
    for (final var type : types) collectImports(type, named);
    final var reserved = new HashSet<>(taken);
    reserved.addAll(IMPLICIT);
    reserved.add(blueprint.getSimpleName().toString());
    reserved.add(name);
    final var names = new Names(named, reserved);
    try {
      final var file = processingEnv.getFiler().createSourceFile(qualified, blueprint);
      try (final var out = new PrintWriter(file.openWriter())) {
        if (!pkg.isEmpty()) out.println("package " + pkg + ";");
        for (final var imported : names.imports()) out.println("import " + imported + ";");
        final var beanName = processingEnv
          .getElementUtils()
          .getElementValuesWithDefaults(config)
          .entrySet()
          .stream()
          .filter(entry -> entry.getKey().getSimpleName().contentEquals("value"))
          .map(entry -> entry.getValue().toString())
          .findFirst()
          .orElse("\"\"");
        out.println("@" + names.ref(SPRING_COMPONENT) + "(" + beanName + ")");
        out.println("public final class " + name + " implements " + blueprint.getSimpleName() + " {");
        body.accept(out, names);
        out.println("}");
      }
    } catch (final IOException e) {
      error(blueprint, "Failed to write " + qualified + ": " + e.getMessage());
    }
  }

  /** The package a qualified name sits in, or the empty string for the default package. */
  private static String packageOf(final String qualifiedName) {
    final var dot = qualifiedName.lastIndexOf('.');
    return dot < 0 ? "" : qualifiedName.substring(0, dot);
  }

  private String packageOf(final Element element) {
    return processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
  }
}
