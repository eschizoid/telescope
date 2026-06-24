package io.github.eschizoid.telescope.codegen;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Emits a reflection-free {@code <X>FromMap} converter for each {@code @FromMap} record or bean: a
 * {@code static X fromMap(Map<String, Object>)} that rebuilds the target (record canonical
 * constructor, or bean builder / no-arg-ctor + setters) with the map values coerced inline, plus a
 * {@code FROM_MAP} {@code ForwardMapper} constant. No {@code SerializedLambda}, no reflection — the
 * generated code is GraalVM native-image clean.
 */
@SupportedAnnotationTypes("io.github.eschizoid.telescope.annotations.FromMap")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class FromMapProcessor extends AbstractTelescopeProcessor {

  private static final String ANNOTATION = "io.github.eschizoid.telescope.annotations.FromMap";

  /** Public no-arg constructor for {@code ServiceLoader} discovery by the Java compiler. */
  public FromMapProcessor() {
    super();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var anno = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
    if (anno == null) return false;
    for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
      if (element.getKind() == ElementKind.RECORD) {
        generateForRecord((TypeElement) element);
      } else if (element.getKind() == ElementKind.CLASS) {
        generateForBean((TypeElement) element);
      } else {
        error(element, "@FromMap is only supported on records and classes");
      }
    }
    return true;
  }

  private void generateForRecord(final TypeElement record) {
    final var name = record.getSimpleName().toString();
    final var components = record.getRecordComponents();
    final var coercions = components
      .stream()
      .map(c -> resolveCoercion(c.asType()))
      .toList();
    final var unchecked = coercions.stream().anyMatch(Coercion::unchecked);

    emitConverter(record, unchecked, out -> {
      final var args = IntStream.range(0, components.size())
        .mapToObj(i -> coercions.get(i).emit("map.get(\"" + components.get(i).getSimpleName() + "\")", 0))
        .collect(Collectors.joining(", "));
      out.println("    return new " + name + "(" + args + ");");
    });
  }

  private void generateForBean(final TypeElement pojo) {
    final var name = pojo.getSimpleName().toString();
    final var props = beanProperties(pojo);
    if (props.isEmpty()) {
      error(pojo, "@FromMap: " + pojo.getQualifiedName() + " has no readable properties (getX()/isX())");
      return;
    }
    final var builder = staticBuilderMethod(pojo);
    final var builderType =
      builder != null && builder.getReturnType().getKind() == TypeKind.DECLARED
        ? (TypeElement) ((DeclaredType) builder.getReturnType()).asElement()
        : null;
    final var useBuilder = builderType != null && hasBuildMethod(builderType);
    if (!useBuilder && !hasPublicNoArgConstructor(pojo)) {
      error(
        pojo,
        "@FromMap: " +
          pojo.getQualifiedName() +
          " needs a static builder() or a public no-arg constructor with setters (field injection isn't " +
          "available to generated code — use Telescope.ofBean for the runtime path)"
      );
      return;
    }
    final var setters = new String[props.size()];
    for (var i = 0; i < props.size(); i++) {
      setters[i] = useBuilder ? builderSetter(builderType, props.get(i).name()) : setterName(pojo, props.get(i).name());
      if (setters[i] == null) {
        error(
          pojo,
          "@FromMap: no " +
            (useBuilder ? "builder method" : "setter") +
            " for property '" +
            props.get(i).name() +
            "' on " +
            pojo.getQualifiedName()
        );
        return;
      }
    }
    final var coercions = props
      .stream()
      .map(p -> resolveCoercion(p.type()))
      .toList();
    final var unchecked = coercions.stream().anyMatch(Coercion::unchecked);

    emitConverter(pojo, unchecked, out -> {
      if (useBuilder) {
        out.print("    return " + name + ".builder()");
        for (var i = 0; i < props.size(); i++) {
          out.print("." + setters[i] + "(" + valueOf(coercions.get(i), props.get(i).name()) + ")");
        }
        out.println(".build();");
      } else {
        out.println("    final var bean = new " + name + "();");
        for (var i = 0; i < props.size(); i++) {
          out.println("    bean." + setters[i] + "(" + valueOf(coercions.get(i), props.get(i).name()) + ");");
        }
        out.println("    return bean;");
      }
    });
  }

  /** The coerced value expression for a property whose map key is its name. */
  private static String valueOf(final Coercion coercion, final String key) {
    return coercion.emit("map.get(\"" + key + "\")", 0);
  }

  /**
   * Emit the shared {@code <X>FromMap} class shell — the fromMap method (body supplied) and
   * FROM_MAP constant.
   */
  private void emitConverter(final TypeElement type, final boolean unchecked, final Consumer<PrintWriter> body) {
    final var pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    final var name = type.getSimpleName().toString();
    final var holder = name + "FromMap";
    final var qualified = pkg.isEmpty() ? holder : pkg + "." + holder;

    final Set<String> imports = new LinkedHashSet<>();
    imports.add("java.util.Map");
    imports.add("io.github.eschizoid.telescope.conversion.ForwardMapper");

    final var javadoc = "Generated by telescope-codegen for @FromMap " + name + ".";
    writeClass(qualified, holder, imports, javadoc, type, out -> {
      if (unchecked) out.println("  @SuppressWarnings(\"unchecked\")");
      out.println("  public static " + name + " fromMap(final Map<String, Object> map) {");
      out.println("    if (map == null) return null;");
      body.accept(out);
      out.println("  }");
      out.println();
      // Map.class is a raw Class<Map>; create wants Class<Map<String, Object>> — same unchecked
      // bridge the runtime Telescope.fromMap makes.
      out.println("  @SuppressWarnings(\"unchecked\")");
      out.println("  public static final ForwardMapper<Map<String, Object>, " + name + "> FROM_MAP =");
      out.println("      ForwardMapper.create(" + holder + "::fromMap, Map.class, " + name + ".class);");
    });
  }

  /** Map a target field type to the expression strategy that coerces a raw map value into it. */
  private Coercion resolveCoercion(final TypeMirror type) {
    return switch (type.getKind()) {
      case INT -> new Coercion.Parse("intValue", "Integer.parseInt", "0");
      case LONG -> new Coercion.Parse("longValue", "Long.parseLong", "0L");
      case DOUBLE -> new Coercion.Parse("doubleValue", "Double.parseDouble", "0.0d");
      case DECLARED -> declaredCoercion((DeclaredType) type);
      default -> new Coercion.Cast(boxedType(type));
    };
  }

  /**
   * Coercion for a declared (reference) type: enum, nested @FromMap, List/Set/Map container, else a
   * cast.
   */
  private Coercion declaredCoercion(final DeclaredType type) {
    final var element = type.asElement();
    if (element.getKind() == ElementKind.ENUM) return new Coercion.EnumOf(boxedType(type));
    if (hasAnnotation(element, ANNOTATION)) return new Coercion.Nested(boxedType(type) + "FromMap");
    final var listElement = singleArgOf(type, "java.util.List");
    if (listElement != null) return new Coercion.Listed(resolveCoercion(listElement));
    final var setElement = singleArgOf(type, "java.util.Set");
    if (setElement != null) return new Coercion.Setted(resolveCoercion(setElement));
    if (isErasure(type, "java.util.Map") && type.getTypeArguments().size() == 2) {
      final var args = type.getTypeArguments();
      return new Coercion.MapValues(boxedType(args.get(0)), resolveCoercion(args.get(1)));
    }
    return new Coercion.Cast(boxedType(type));
  }

  /**
   * The sole type argument of {@code type} when its erasure is exactly {@code rawFqn}, else null.
   */
  private TypeMirror singleArgOf(final DeclaredType type, final String rawFqn) {
    if (!isErasure(type, rawFqn)) return null;
    final var args = type.getTypeArguments();
    return args.size() == 1 ? args.get(0) : null;
  }

  /** Whether {@code type}'s erasure is exactly the raw type named {@code rawFqn}. */
  private boolean isErasure(final DeclaredType type, final String rawFqn) {
    final var types = processingEnv.getTypeUtils();
    final var raw = processingEnv.getElementUtils().getTypeElement(rawFqn);
    return raw != null && types.isSameType(types.erasure(type), types.erasure(raw.asType()));
  }
}
