package io.github.eschizoid.telescope.codegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/** Generates Spring components for bridge-backed mappers and reusable record paths. */
@SupportedAnnotationTypes(
  { "io.github.eschizoid.telescope.spring.TelescopeMapper", "io.github.eschizoid.telescope.spring.TelescopeTransform" }
)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class TelescopeMapperProcessor extends AbstractTelescopeProcessor {

  private static final String MAPPER = "io.github.eschizoid.telescope.spring.TelescopeMapper";
  private static final String TRANSFORM = "io.github.eschizoid.telescope.spring.TelescopeTransform";
  private static final String BRIDGE = "io.github.eschizoid.telescope.annotations.Bridge";
  private static final String PATH = "io.github.eschizoid.telescope.spring.TelescopePath";

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
    final var from = declaredType(config, "from");
    final var to = declaredType(config, "to");
    if (from == null || to == null) {
      error(blueprint, "@TelescopeMapper from/to must be model classes");
      return;
    }
    final var methods = abstractMethods(blueprint);
    if (
      methods.size() != 1 ||
      methods.getFirst().getParameters().size() != 1 ||
      !same(methods.getFirst().getParameters().getFirst().asType(), from) ||
      !same(methods.getFirst().getReturnType(), to)
    ) {
      error(blueprint, "@TelescopeMapper requires one method accepting " + from + " and returning " + to);
      return;
    }
    final var source = (TypeElement) from.asElement();
    final var bridgeClass =
      processingEnv.getElementUtils().getPackageOf(source).getQualifiedName() + "." + source.getSimpleName() + "Bridge";
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
    if ((bridgeTarget == null || !same(bridgeTarget, to)) && !compiledForward) {
      error(blueprint, "@TelescopeMapper requires a generated @Bridge for " + from + " -> " + to);
      return;
    }
    final var method = methods.getFirst();
    emit(blueprint, out -> {
      out.println("  @Override public " + to + " " + method.getSimpleName() + "(final " + from + " input) {");
      out.println("    return " + bridgeClass + ".forward(input);");
      out.println("  }");
    });
  }

  private void generatePath(final Element element) {
    final var blueprint = interfaceType(element, "@TelescopeTransform");
    if (blueprint == null) return;
    final var config = annotation(blueprint, TRANSFORM);
    if (!abstractMethods(blueprint).isEmpty()) {
      error(
        blueprint,
        "@TelescopeTransform interface must declare no abstract methods; TelescopePath supplies the API"
      );
      return;
    }
    final var from = declaredType(config, "from");
    final var to = declaredType(config, "to");
    final var rawPath = stringValue(config, "path");
    if (from == null || to == null || rawPath == null || rawPath.isBlank()) {
      error(blueprint, "@TelescopeTransform requires from, to, and a nonempty path");
      return;
    }
    final var pathInterface = processingEnv.getElementUtils().getTypeElement(PATH);
    if (
      pathInterface == null ||
      blueprint.getInterfaces().size() != 1 ||
      !(blueprint.getInterfaces().getFirst() instanceof DeclaredType declared) ||
      !processingEnv
        .getTypeUtils()
        .isSameType(
          processingEnv.getTypeUtils().erasure(declared),
          processingEnv.getTypeUtils().erasure(pathInterface.asType())
        ) ||
      declared.getTypeArguments().size() != 2 ||
      !same(declared.getTypeArguments().get(0), from) ||
      !same(declared.getTypeArguments().get(1), to)
    ) {
      error(blueprint, "@TelescopeTransform must extend TelescopePath<" + from + ", " + to + ">");
      return;
    }
    final var hops = new ArrayList<String>();
    TypeMirror current = from;
    for (final var segment : rawPath.split("\\.", -1)) {
      if (
        !(current instanceof DeclaredType dt) ||
        !(dt.asElement() instanceof TypeElement record) ||
        record.getKind() != ElementKind.RECORD
      ) {
        error(blueprint, "@TelescopeTransform path currently supports record fields only: " + rawPath);
        return;
      }
      final var component = record
        .getRecordComponents()
        .stream()
        .filter(c -> c.getSimpleName().contentEquals(segment))
        .findFirst()
        .orElse(null);
      if (component == null) {
        error(blueprint, "@TelescopeTransform path segment '" + segment + "' is not a field of " + record);
        return;
      }
      hops.add(record.getQualifiedName() + "::" + segment);
      current = component.asType();
    }
    if (!sameBoxed(current, to)) {
      error(blueprint, "@TelescopeTransform path ends at " + current + ", expected " + to);
      return;
    }
    emit(blueprint, out -> {
      out.println("  private final io.github.eschizoid.telescope.Telescope<" + from + ", " + to + "> path =");
      out.print("    io.github.eschizoid.telescope.Telescope.of(" + from + ".class)");
      for (final var hop : hops) out.print(".field(" + hop + ")");
      out.println(";");
      out.println("  @Override public io.github.eschizoid.telescope.Telescope<" + from + ", " + to + "> path() {");
      out.println("    return path;");
      out.println("  }");
    });
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
    if (type.getNestingKind() != javax.lang.model.element.NestingKind.TOP_LEVEL) {
      error(element, annotation + " requires a top-level interface");
      return null;
    }
    return type;
  }

  private List<ExecutableElement> abstractMethods(final TypeElement type) {
    return ElementFilter.methodsIn(type.getEnclosedElements())
      .stream()
      .filter(m -> m.getModifiers().contains(Modifier.ABSTRACT))
      .toList();
  }

  private boolean same(final TypeMirror left, final TypeMirror right) {
    return processingEnv.getTypeUtils().isSameType(left, right);
  }

  private boolean sameBoxed(final TypeMirror left, final TypeMirror right) {
    final var types = processingEnv.getTypeUtils();
    final var a = left instanceof PrimitiveType p ? types.boxedClass(p).asType() : left;
    final var b = right instanceof PrimitiveType p ? types.boxedClass(p).asType() : right;
    return types.isSameType(a, b);
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

  private static String stringValue(final AnnotationMirror mirror, final String key) {
    final var value = value(mirror, key);
    return value instanceof String s ? s : null;
  }

  private void emit(final TypeElement blueprint, final java.util.function.Consumer<PrintWriter> body) {
    final var pkg = processingEnv.getElementUtils().getPackageOf(blueprint).getQualifiedName().toString();
    final var name = blueprint.getSimpleName() + "Impl";
    final var qualified = pkg.isEmpty() ? name : pkg + "." + name;
    try {
      final var file = processingEnv.getFiler().createSourceFile(qualified, blueprint);
      try (final var out = new PrintWriter(file.openWriter())) {
        if (!pkg.isEmpty()) out.println("package " + pkg + ";");
        out.println("@org.springframework.stereotype.Component");
        out.println("public final class " + name + " implements " + blueprint.getQualifiedName() + " {");
        body.accept(out);
        out.println("}");
      }
    } catch (final IOException e) {
      error(blueprint, "Failed to write " + qualified + ": " + e.getMessage());
    }
  }
}
