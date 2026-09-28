package io.github.eschizoid.telescope.codegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;

/** Generates Spring components for structural mappers and reusable record paths. */
@SupportedAnnotationTypes(
  {
    "io.github.eschizoid.telescope.spring.TelescopeMapper", "io.github.eschizoid.telescope.spring.TelescopeTransformer",
  }
)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class TelescopeMapperProcessor extends AbstractTelescopeProcessor {

  private static final String MAPPER = "io.github.eschizoid.telescope.spring.TelescopeMapper";
  private static final String TRANSFORM = "io.github.eschizoid.telescope.spring.TelescopeTransformer";
  private static final String BRIDGE = "io.github.eschizoid.telescope.annotations.Bridge";
  private static final String PATH = "io.github.eschizoid.telescope.spring.TelescopeTransformation";
  private static final String PROJECTION = "io.github.eschizoid.telescope.spring.TelescopeProjection";
  private static final String CUSTOMIZER = "io.github.eschizoid.telescope.spring.TelescopeCustomizer";

  /** Public constructor for processor discovery. */
  public TelescopeMapperProcessor() {
    super();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment round) {
    final var mapper = processingEnv.getElementUtils().getTypeElement(MAPPER);
    if (mapper != null) for (final var element : round.getElementsAnnotatedWith(mapper)) generateMapper(element);
    final var transform = processingEnv.getElementUtils().getTypeElement(TRANSFORM);
    if (transform != null) for (final var element : round.getElementsAnnotatedWith(transform)) generatePath(element);
    return true;
  }

  private void generateMapper(final Element element) {
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
    final var methods = abstractMethods(blueprint);
    final var mappingMethods = methods
      .stream()
      .filter(m -> m.getSimpleName().contentEquals("map"))
      .toList();
    final var additionMethods = methods
      .stream()
      .filter(m -> m.getSimpleName().contentEquals("addTransformer"))
      .toList();
    if (
      mappingMethods.size() != 1 ||
      methods.size() != 1 + (projection == null ? 0 : 3) ||
      additionMethods.size() != (projection == null ? 0 : 1) ||
      !mappingMethods.getFirst().getTypeParameters().isEmpty() ||
      signature(blueprint, mappingMethods.getFirst()).getParameterTypes().size() != 1 ||
      !same(signature(blueprint, mappingMethods.getFirst()).getParameterTypes().getFirst(), from) ||
      !same(signature(blueprint, mappingMethods.getFirst()).getReturnType(), to)
    ) {
      error(blueprint, "@TelescopeMapper requires one method accepting " + from + " and returning " + to);
      return;
    }
    final var declaredTransformers = transformerTypes(config, blueprint, from);
    if (declaredTransformers == null) return;
    final var source = (TypeElement) from.asElement();
    final var sourcePackage = processingEnv.getElementUtils().getPackageOf(source).getQualifiedName().toString();
    final var bridgeName = source.getSimpleName() + "Bridge";
    final var bridgeClass = sourcePackage.isEmpty() ? bridgeName : sourcePackage + "." + bridgeName;
    final var bridge = annotation(source, BRIDGE);
    final var bridgeTarget = bridge == null ? null : declaredType(bridge, "value");
    final var compiledBridge = processingEnv.getElementUtils().getTypeElement(bridgeClass);
    final var compiledForward =
      compiledBridge != null &&
      ElementFilter.methodsIn(compiledBridge.getEnclosedElements())
        .stream()
        .anyMatch(
          m ->
            m.getSimpleName().contentEquals("forward") &&
            m.getModifiers().contains(Modifier.PUBLIC) &&
            m.getModifiers().contains(Modifier.STATIC) &&
            m.getParameters().size() == 1 &&
            same(m.getParameters().getFirst().asType(), from) &&
            same(m.getReturnType(), to)
        );
    if (projection == null && (bridgeTarget == null || !same(bridgeTarget, to)) && !compiledForward) {
      error(blueprint, "@TelescopeMapper requires a generated @Bridge for " + from + " -> " + to);
      return;
    }
    final var method = mappingMethods.getFirst();
    final var model = simple(from);
    final var focus = "TelescopeTransformation<" + model + ", ?>";
    final var refs = new ArrayList<String>();
    final var customTranslate = overridesTranslate(blueprint, projection);
    final var useBridge = !customTranslate && ((bridgeTarget != null && same(bridgeTarget, to)) || compiledForward);
    if (useBridge) refs.add(bridgeClass);
    else {
      refs.add("io.github.eschizoid.telescope.Telescope");
      if (projection != null) {
        refs.add("io.github.eschizoid.telescope.conversion.Mapper");
        refs.add("io.github.eschizoid.telescope.conversion.MapperBuilder");
      }
    }
    if (projection != null || !declaredTransformers.isEmpty()) {
      refs.add("io.github.eschizoid.telescope.spring.TelescopeTransformation");
      refs.add("java.util.List");
    }
    if (projection != null) {
      refs.add(PROJECTION);
      refs.add(CUSTOMIZER);
      refs.add("org.springframework.beans.factory.ObjectProvider");
      refs.add("java.util.ArrayList");
      refs.add("java.util.Objects");
    }
    final var allTypes = new ArrayList<TypeMirror>(List.of(from, to));
    allTypes.addAll(declaredTransformers);
    emit(blueprint, config, allTypes, refs, out -> {
      if (!useBridge) {
        if (projection != null) {
          out.println("  private final Mapper<" + model + ", " + simple(to) + "> mapping = createMapping();");
        } else {
          out.println(
            "  private final Telescope<" +
              model +
              ", " +
              simple(to) +
              "> mapping = Telescope.map(" +
              model +
              ".class, " +
              simple(to) +
              ".class);"
          );
        }
      }
      if (projection != null || !declaredTransformers.isEmpty()) {
        out.println("  private final List<" + focus + "> transformers;");
      }
      if (projection != null) {
        // Open while the constructor runs customizers; null afterwards, which fixes the list.
        out.println("  private ArrayList<" + focus + "> pending;");
        out.print("  public " + blueprint.getSimpleName() + "Impl(");
        for (var i = 0; i < declaredTransformers.size(); i++) {
          out.print("final " + simple(declaredTransformers.get(i)) + " transformer" + i + ", ");
        }
        out.println("final ObjectProvider<TelescopeCustomizer<" + blueprint.getSimpleName() + ">> customizers) {");
        out.print("    pending = new ArrayList<>(");
        if (!declaredTransformers.isEmpty()) {
          out.print("List.of(");
          for (var i = 0; i < declaredTransformers.size(); i++) {
            if (i != 0) out.print(", ");
            out.print("transformer" + i);
          }
          out.print(")");
        }
        out.println(");");
        out.println("    customizers.orderedStream().forEach(customizer -> customizer.customize(this));");
        out.println("    this.transformers = List.copyOf(pending);");
        out.println("    pending = null;");
        out.println("  }");
      } else if (!declaredTransformers.isEmpty()) {
        out.print("  public " + blueprint.getSimpleName() + "Impl(");
        for (var i = 0; i < declaredTransformers.size(); i++) {
          if (i != 0) out.print(", ");
          out.print("final " + simple(declaredTransformers.get(i)) + " transformer" + i);
        }
        out.println(") {");
        out.print("    this.transformers = List.of(");
        for (var i = 0; i < declaredTransformers.size(); i++) {
          if (i != 0) out.print(", ");
          out.print("transformer" + i);
        }
        out.println(");");
        out.println("  }");
      }
      out.println("  @Override public " + simple(to) + " " + method.getSimpleName() + "(final " + model + " input) {");
      if (projection == null && declaredTransformers.isEmpty()) {
        out.println("    return " + (useBridge ? bridgeName + ".forward(input)" : "mapping.forward(input)") + ";");
      } else {
        out.println("    if (input == null) return null;");
        out.println("    final List<" + focus + "> current = transformers;");
        out.println(
          "    if (current.isEmpty()) return " +
            (useBridge ? bridgeName + ".forward(input)" : "mapping.forward(input)") +
            ";"
        );
        out.println("    " + model + " value = input;");
        out.println("    for (int i = 0, n = current.size(); i < n; i++) value = current.get(i).apply(value);");
        out.println("    return " + (useBridge ? bridgeName + ".forward(value)" : "mapping.forward(value)") + ";");
      }
      out.println("  }");
      if (projection != null) {
        if (useBridge) {
          out.println(
            "  @Override public " +
              model +
              " backward(final " +
              simple(to) +
              " input) { return " +
              bridgeName +
              ".backward(input); }"
          );
          out.println(
            "  @Override public " + model + " patch(final " + model + " source, final " + simple(to) + " partial) {"
          );
          // A bridge is an Iso: writing through it rebuilds the whole source from the partial,
          // so it
          // cannot keep the base fields a sparse overlay promises to keep.
          out.println(
            "    throw new UnsupportedOperationException(\"" +
              blueprint.getSimpleName() +
              " maps through the generated " +
              bridgeName +
              ", which rebuilds the whole source from the partial and cannot keep the fields it leaves" +
              " null. Override translate so the projection maps through a core Mapper, which patches.\");"
          );
          out.println("  }");
        } else {
          out.println(
            "  @Override public " +
              model +
              " backward(final " +
              simple(to) +
              " input) { return mapping.backward(input); }"
          );
          out.println(
            "  @Override public " +
              model +
              " patch(final " +
              model +
              " source, final " +
              simple(to) +
              " partial) { return mapping.patch(source, partial); }"
          );
        }
      }
      if (projection != null && !useBridge) {
        out.println("  private Mapper<" + model + ", " + simple(to) + "> createMapping() {");
        out.println(
          "    final MapperBuilder<" +
            model +
            ", " +
            simple(to) +
            "> builder = Telescope.mapperBuilder(" +
            model +
            ".class, " +
            simple(to) +
            ".class);"
        );
        out.println("    " + blueprint.getSimpleName() + ".super.translate(builder);");
        out.println("    return builder.build();");
        out.println("  }");
      }
      if (projection != null) {
        out.println(
          "  @Override public TelescopeProjection<" +
            model +
            ", " +
            simple(to) +
            "> addTransformer(final " +
            focus +
            " transformer) {"
        );
        out.println("    Objects.requireNonNull(transformer, \"transformer must not be null\");");
        out.println(
          "    if (pending == null) throw new IllegalStateException(\"" +
            blueprint.getSimpleName() +
            " transformers are fixed once the bean is constructed; register them from a TelescopeCustomizer<" +
            blueprint.getSimpleName() +
            "> bean\");"
        );
        out.println("    pending.add(transformer);");
        out.println("    return this;");
        out.println("  }");
      }
    });
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
      processingEnv.getElementUtils().getTypeElement("io.github.eschizoid.telescope.spring.Transformation"),
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
    emit(
      blueprint,
      config,
      List.of(from, to),
      List.of(
        "io.github.eschizoid.telescope.Telescope",
        "io.github.eschizoid.telescope.spring.Transformation",
        "java.util.Objects"
      ),
      out -> {
        final var pathType = "Telescope<" + simple(from) + ", " + simple(to) + ">";
        out.println("  private final " + pathType + " path =");
        out.println(
          "    Objects.requireNonNull(" + blueprint.getSimpleName() + ".super.path(), \"path() must not return null\");"
        );
        out.println("  @Override public " + pathType + " path() {");
        out.println("    return path;");
        out.println("  }");
        final var transformationType = "Transformation<" + simple(to) + ">";
        out.println("  private final " + transformationType + " transformation =");
        out.println(
          "    Objects.requireNonNull(" +
            blueprint.getSimpleName() +
            ".super.transform(), \"transform() must not return null\");"
        );
        out.println("  @Override public " + transformationType + " transform() {");
        out.println("    return transformation;");
        out.println("  }");
        out.println("  @Override public " + simple(from) + " apply(final " + simple(from) + " input) {");
        out.println("    return path.update(input, transformation);");
        out.println("  }");
      }
    );
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

  private TypeElement interfaceType(final Element element, final String annotation) {
    if (
      !(element instanceof TypeElement type) ||
      type.getKind() != ElementKind.INTERFACE ||
      !type.getTypeParameters().isEmpty()
    ) {
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

  private static String simple(final TypeMirror type) {
    if (type instanceof DeclaredType declared) {
      final var name =
        declared.getEnclosingType() instanceof DeclaredType enclosing &&
        !declared.asElement().getModifiers().contains(Modifier.STATIC)
          ? simple(enclosing) + "." + declared.asElement().getSimpleName()
          : declared.asElement().getSimpleName().toString();
      return declared.getTypeArguments().isEmpty()
        ? name
        : name +
          "<" +
          declared.getTypeArguments().stream().map(TelescopeMapperProcessor::simple).collect(Collectors.joining(", ")) +
          ">";
    }
    if (type instanceof ArrayType array) return simple(array.getComponentType()) + "[]";
    if (type instanceof WildcardType wildcard) {
      if (wildcard.getExtendsBound() != null) return "? extends " + simple(wildcard.getExtendsBound());
      if (wildcard.getSuperBound() != null) return "? super " + simple(wildcard.getSuperBound());
      return "?";
    }
    return type.toString();
  }

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

  private void emit(
    final TypeElement blueprint,
    final AnnotationMirror config,
    final List<? extends TypeMirror> types,
    final List<String> references,
    final Consumer<PrintWriter> body
  ) {
    final var pkg = processingEnv.getElementUtils().getPackageOf(blueprint).getQualifiedName().toString();
    final var name = blueprint.getSimpleName() + "Impl";
    final var qualified = pkg.isEmpty() ? name : pkg + "." + name;
    final var imports = new TreeSet<String>();
    imports.add("org.springframework.stereotype.Component");
    imports.addAll(references);
    for (final var type : types) collectImports(type, imports);
    final var names = new HashSet<String>();
    names.add(blueprint.getSimpleName().toString());
    names.add(name);
    for (final var imported : imports) {
      final var simple = imported.substring(imported.lastIndexOf('.') + 1);
      if (!names.add(simple)) {
        error(blueprint, "Generated implementation has conflicting simple type name: " + simple);
        return;
      }
    }
    try {
      final var file = processingEnv.getFiler().createSourceFile(qualified, blueprint);
      try (final var out = new PrintWriter(file.openWriter())) {
        if (!pkg.isEmpty()) out.println("package " + pkg + ";");
        for (final var imported : imports) if (imported.contains(".")) out.println("import " + imported + ";");
        final var beanName = processingEnv
          .getElementUtils()
          .getElementValuesWithDefaults(config)
          .entrySet()
          .stream()
          .filter(entry -> entry.getKey().getSimpleName().contentEquals("value"))
          .map(entry -> entry.getValue().toString())
          .findFirst()
          .orElse("\"\"");
        out.println("@Component(" + beanName + ")");
        out.println("public final class " + name + " implements " + blueprint.getSimpleName() + " {");
        body.accept(out);
        out.println("}");
      }
    } catch (final IOException e) {
      error(blueprint, "Failed to write " + qualified + ": " + e.getMessage());
    }
  }
}
