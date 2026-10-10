package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.MapValueTypes;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/**
 * Emits a reflection-free {@code <X>FromMap} converter for each {@code @FromMap} record or bean: a
 * {@code static X fromMap(Map<String, Object>)} that rebuilds the target (record canonical
 * constructor, or a bean's builder, all-args constructor or no-arg-ctor + setters, in the order the
 * runtime writer tries them) with the map values coerced inline, plus a {@code FROM_MAP} {@code
 * ForwardMapper} constant. No {@code SerializedLambda}, no reflection — the generated code is
 * GraalVM native-image clean.
 */
@SupportedAnnotationTypes("io.github.eschizoid.telescope.annotations.FromMap")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class FromMapProcessor extends AbstractTelescopeProcessor {

  private static final String ANNOTATION = "io.github.eschizoid.telescope.annotations.FromMap";

  private static final String REFUSE_MISSING = "__refuseMissing";

  private static final String MAP_TYPE = "java.util.Map<java.lang.String, java.lang.Object>";

  private static final String FORWARD_MAPPER = "io.github.eschizoid.telescope.conversion.ForwardMapper";

  private static final String FROM_MAP_PROVIDER = "io.github.eschizoid.telescope.conversion.FromMapProvider";

  private static final String PROVIDER = "Provider";

  // @FromMap targets carrying a Lombok trigger are deferred to processingOver(): in round 1 Lombok
  // hasn't synthesized the getters/setters yet, so beanProperties() would see "no readable
  // properties". By the final round Lombok is done patching. Cleared after the drain so a reused
  // processor instance starts clean.
  private final Set<TypeElement> pending = new LinkedHashSet<>();

  // Binary names of the providers nested in the binders this compilation emitted, each with the
  // type its binder builds, registered in
  // META-INF/services once every binder, deferred ones included, has been written.
  private final Map<String, TypeElement> providers = new LinkedHashMap<>();

  /** Public no-arg constructor for {@code ServiceLoader} discovery by the Java compiler. */
  public FromMapProcessor() {
    super();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var anno = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
    if (anno == null) return false;
    for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
      if (!roundEnv.processingOver() && carriesLombokTrigger(element)) pending.add((TypeElement) element);
      else generate(element);
    }
    if (roundEnv.processingOver()) {
      pending.forEach(this::generate);
      pending.clear();
      writeProviderServices();
      providers.clear();
    }
    return true;
  }

  private void generate(final Element element) {
    // The binder is emitted at package level and names its target by simple name, which resolves
    // to nothing there for a nested type. Without this it emits source that cannot compile and
    // says nothing, so what an adopter sees is javac reporting an unknown symbol inside a file
    // they never wrote. Two nested types sharing a simple name in one package would also generate
    // the same binder and overwrite each other. @Focus and @BeanFocus refuse the same shape.
    if (element.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
      error(
        element,
        "@FromMap is only supported on top-level types (the generated binder is emitted beside " +
          "the package and cannot name a nested type)"
      );
      return;
    }
    if (element.getKind() == ElementKind.RECORD) generateForRecord((TypeElement) element);
    else if (element.getKind() == ElementKind.CLASS) generateForBean((TypeElement) element);
    else error(element, "@FromMap is only supported on records and classes");
  }

  private void generateForRecord(final TypeElement record) {
    final var components = record.getRecordComponents();
    final var coercions = components
      .stream()
      .map(c -> resolveCoercion(c.asType()))
      .toList();
    var coercible = true;
    for (var i = 0; i < components.size(); i++) {
      final var reason = coercions.get(i).firstUnsupported();
      if (reason.isPresent()) {
        error(components.get(i), "@FromMap: " + reason.get());
        coercible = false;
      }
    }
    if (!coercible) return;
    final var componentNames = components
      .stream()
      .map(c -> c.getSimpleName().toString())
      .toList();
    final var required = requiredNames(record, componentNames, "component");
    if (required == null) return;
    final var unchecked = coercions.stream().anyMatch(Coercion::unchecked);
    final var helpers = new LinkedHashMap<String, String>();
    for (final var coercion : coercions) helpers.putAll(coercion.helpers());
    addRefusal(record, required, helpers);

    emitConverter(record, unchecked, helpers, required, out -> {
      for (final var component : components) hoist(out, component.getSimpleName().toString());
      emitRefusalCall(out, required, "component");
      final var args = IntStream.range(0, components.size())
        .mapToObj(i -> coercions.get(i).emit(local(components.get(i).getSimpleName().toString()), 0))
        .collect(Collectors.joining(", "));
      out.println("    return new " + record.getQualifiedName() + "(" + args + ");");
    });
  }

  private void generateForBean(final TypeElement pojo) {
    final var props = beanProperties(pojo);
    if (props.isEmpty()) {
      error(pojo, "@FromMap: " + pojo.getQualifiedName() + " has no readable properties (getX()/isX())");
      return;
    }
    // The binder is emitted into the bean's own package, so a protected or package-private no-arg
    // constructor is reachable from it; only private is not. The strategy is the one the runtime
    // fromMap's writer picks for the same bean.
    final var rebuild = beanRebuildFor(pojo, props, "@FromMap");
    if (rebuild == null) return;
    final var members = rebuild.members();
    final var coercions = props
      .stream()
      .map(p -> resolveCoercion(p.type()))
      .toList();
    var coercible = true;
    for (var i = 0; i < props.size(); i++) {
      final var reason = coercions.get(i).firstUnsupported();
      if (reason.isPresent()) {
        error(pojo, "@FromMap: property '" + props.get(i).name() + "' — " + reason.get());
        coercible = false;
      }
    }
    if (!coercible) return;
    final var required = requiredNames(pojo, props.stream().map(Prop::name).toList(), "property");
    if (required == null) return;
    final var unchecked = coercions.stream().anyMatch(Coercion::unchecked);
    final var helpers = new LinkedHashMap<String, String>();
    for (final var coercion : coercions) helpers.putAll(coercion.helpers());
    addRefusal(pojo, required, helpers);

    final Function<Prop, String> coerced = prop -> valueOf(coercions.get(props.indexOf(prop)), prop.name());
    emitConverter(pojo, unchecked, helpers, required, out -> {
      for (final var prop : props) hoist(out, prop.name());
      emitRefusalCall(out, required, "property");
      final var target = pojo.getQualifiedName().toString();
      switch (rebuild.strategy()) {
        case BUILDER -> {
          out.print("    return " + target + ".builder()");
          for (var i = 0; i < props.size(); i++) {
            if (members[i] != null) out.print("." + members[i] + "(" + coerced.apply(props.get(i)) + ")");
          }
          out.println(".build();");
        }
        case CONSTRUCTOR -> {
          final var prelude = new StringBuilder();
          final var args = guardedArguments(props, rebuild.ctorOrder(), rebuild, coerced, prelude);
          if (!prelude.isEmpty()) out.println("    " + prelude.toString().strip());
          out.println("    return new " + target + "(" + String.join(", ", args) + ");");
        }
        case SETTERS -> {
          out.println("    final var bean = new " + target + "();");
          for (var i = 0; i < props.size(); i++) {
            out.println("    bean." + members[i] + "(" + valueOf(coercions.get(i), props.get(i).name()) + ");");
          }
          out.println("    return bean;");
        }
      }
    });
  }

  /**
   * The names {@code @FromMap(required = ...)} lists on {@code type}, in declaration order of the
   * type's own slots, or null after reporting one that is not among {@code slots}.
   */
  private List<String> requiredNames(final TypeElement type, final List<String> slots, final String kind) {
    final var anno = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
    final var listed = new ArrayList<String>();
    for (final var am : type.getAnnotationMirrors()) {
      if (!am.getAnnotationType().asElement().equals(anno)) continue;
      for (final var entry : am.getElementValues().entrySet()) {
        if (!entry.getKey().getSimpleName().contentEquals("required")) continue;
        @SuppressWarnings("unchecked")
        final var values = (List<? extends AnnotationValue>) entry.getValue().getValue();
        for (final var value : values) listed.add((String) value.getValue());
      }
    }
    var known = true;
    final var seen = new HashSet<String>();
    for (final var name : listed) {
      if (!seen.add(name)) {
        error(type, "@FromMap: required names '" + name + "' more than once");
        known = false;
        continue;
      }
      if (slots.contains(name)) continue;
      error(
        type,
        "@FromMap: required names '" +
          name +
          "', which is not a " +
          kind +
          " of " +
          type.getSimpleName() +
          ". Known: " +
          slots
      );
      known = false;
    }
    if (!known) return null;
    return slots.stream().filter(listed::contains).toList();
  }

  /**
   * The helper the binder calls once a required key is found missing, with one entry per required
   * slot and null where its key carried a value. It names every missing key in one refusal, worded
   * as the runtime {@code fromMap} words it after the binder's own prefix.
   *
   * <p>It names the JDK types it uses by their qualified names and adds no import, because an
   * import would shadow a type of the same simple name in the target's package, the target itself
   * included.
   */
  private static void addRefusal(
    final TypeElement type,
    final List<String> required,
    final Map<String, String> helpers
  ) {
    if (required.isEmpty()) return;
    final var name = type.getSimpleName().toString();
    helpers.put(
      REFUSE_MISSING,
      "private static void " +
        REFUSE_MISSING +
        "(final java.lang.String... missing) {\n" +
        "  final java.lang.StringBuilder named = new java.lang.StringBuilder();\n" +
        "  int count = 0;\n" +
        "  for (final java.lang.String entry : missing) {\n" +
        "    if (entry == null) continue;\n" +
        "    if (count++ > 0) named.append(\", \");\n" +
        "    named.append(entry);\n" +
        "  }\n" +
        "  throw new java.lang.IllegalArgumentException(\n" +
        "    \"" +
        name +
        "FromMap.fromMap: the map carries no value for required \" + (count == 1 ? \"key \" : \"keys \") + named + \" of " +
        name +
        "\"\n" +
        "  );\n" +
        "}"
    );
  }

  /**
   * The refusal for a map missing a required key, made after every key is read once. The guard
   * tests the hoisted locals, so a map that carries every required key allocates nothing for it.
   */
  private static void emitRefusalCall(final PrintWriter out, final List<String> required, final String kind) {
    if (required.isEmpty()) return;
    final var guard = required
      .stream()
      .map(slot -> local(slot) + " == null")
      .collect(Collectors.joining(" || "));
    final var args = required
      .stream()
      .map(slot -> local(slot) + " == null ? \"\\\"" + slot + "\\\" (" + kind + " '" + slot + "')\" : null")
      .collect(Collectors.joining(", "));
    out.println("    if (" + guard + ") " + REFUSE_MISSING + "(" + args + ");");
  }

  /** The coerced value expression for a property, read from the local its key was hoisted into. */
  private static String valueOf(final Coercion coercion, final String key) {
    return coercion.emit(local(key), 0);
  }

  /**
   * The local a map key is read into, once, before any coercion looks at it.
   *
   * <p>A coercion names the expression it is given as many times as its shape needs — the parse arm
   * reaches for it three times — so passing the lookup itself would hash the key once per mention.
   * Against a computing or concurrent map those reads can also disagree, which turns a value that
   * changed between them into a parse failure on a map nobody mutated incorrectly.
   *
   * <p>The prefix is the one the bridge emitter uses for the same reason, and cannot collide: the
   * method has only {@code map} in scope.
   */
  private static String local(final String key) {
    return "__m_" + key;
  }

  private static void hoist(final PrintWriter out, final String key) {
    out.println("    final java.lang.Object " + local(key) + " = map.get(\"" + key + "\");");
  }

  /**
   * Emit the shared {@code <X>FromMap} class shell — the fromMap method (body supplied) and
   * FROM_MAP constant.
   */
  private void emitConverter(
    final TypeElement type,
    final boolean unchecked,
    final Map<String, String> helpers,
    final List<String> required,
    final Consumer<PrintWriter> body
  ) {
    final var pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    final var name = type.getSimpleName().toString();
    final var holder = name + "FromMap";
    // The binder lives in the target's package and imports nothing, so every type it names is
    // written by its qualified name, java.lang included: there a simple name means whatever the
    // package declares, and an import would shadow a package type of the same name or collide with
    // a component type's own. The target is one of the names it writes.
    final var ref = type.getQualifiedName().toString();
    final var qualified = pkg.isEmpty() ? holder : pkg + "." + holder;
    providers.put(qualified + "$" + PROVIDER, type);

    final var javadoc = "Generated by telescope-codegen for @FromMap " + name + ".";
    writeClass(qualified, holder, Set.of(), javadoc, type, out -> {
      if (unchecked) out.println("  @java.lang.SuppressWarnings(\"unchecked\")");
      out.println("  public static " + ref + " fromMap(final " + MAP_TYPE + " map) {");
      out.println("    if (map == null) return null;");
      body.accept(out);
      out.println("  }");
      // Beside the binder rather than inside it: a coercion emits an expression, so a
      // conversion
      // that wants a loop has nowhere else to put one. Contributed by name, so two fields of
      // the
      // same shape ask for the same helper and get one copy.
      for (final var helper : helpers.values()) {
        out.println();
        out.println(helper.indent(2).stripTrailing());
      }
      out.println();
      // Map.class is a raw Class<Map>; create wants Class<Map<String, Object>> — same unchecked
      // bridge the runtime Telescope.fromMap makes.
      out.println("  @java.lang.SuppressWarnings(\"unchecked\")");
      out.println("  public static final " + FORWARD_MAPPER + "<" + MAP_TYPE + ", " + ref + "> FROM_MAP =");
      out.println(
        "      " + FORWARD_MAPPER + ".create(" + holder + "::fromMap, java.util.Map.class, " + ref + ".class);"
      );
      out.println();
      out.println("  /** Registers this binder, so a runtime fromMap knows " + name + " has one. */");
      out.println("  public static final class " + PROVIDER + " implements " + FROM_MAP_PROVIDER + " {");
      out.println();
      out.println("    @java.lang.Override");
      out.println("    public java.lang.Class<?> targetType() {");
      out.println("      return " + ref + ".class;");
      out.println("    }");
      out.println();
      out.println("    @java.lang.Override");
      out.println("    public " + FORWARD_MAPPER + "<" + MAP_TYPE + ", " + ref + "> binder() {");
      out.println("      return FROM_MAP;");
      out.println("    }");
      out.println();
      out.println("    @java.lang.Override");
      out.println("    public java.util.List<java.lang.String> required() {");
      out.println(
        "      return java.util.List.of(" +
          required
            .stream()
            .map(slot -> "\"" + slot + "\"")
            .collect(Collectors.joining(", ")) +
          ");"
      );
      out.println("    }");
      out.println("  }");
    });
  }

  /**
   * Whether {@code type} has a binder this processor generated in an earlier compilation. The
   * annotation is source-retained, so a type read from a class file no longer carries it; what it
   * leaves is a top-level {@code <Name>FromMap} beside it whose nested {@code Provider} implements
   * {@code FromMapProvider}, the same registration the runtime {@code fromMap} looks for. A class
   * that only shares the binder's name has no such provider and does not count.
   */
  private boolean hasGeneratedBinder(final TypeElement type) {
    if (type.getNestingKind().isNested()) return false;
    final var elements = processingEnv.getElementUtils();
    final var binder = elements.getTypeElement(type.getQualifiedName() + "FromMap");
    final var spi = elements.getTypeElement(FROM_MAP_PROVIDER);
    if (binder == null || spi == null) return false;
    final var types = processingEnv.getTypeUtils();
    for (final var member : binder.getEnclosedElements()) {
      if (member.getKind() != ElementKind.CLASS || !member.getSimpleName().contentEquals(PROVIDER)) continue;
      if (types.isAssignable(member.asType(), types.erasure(spi.asType()))) return true;
    }
    return false;
  }

  /**
   * Register every binder this compilation emitted in {@code META-INF/services}, so {@code
   * ServiceLoader} finds its provider on the class path and in a native image.
   */
  private void writeProviderServices() {
    if (providers.isEmpty()) return;
    reportUndeclaredModuleProviders();
    try {
      final var file = processingEnv
        .getFiler()
        .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + FROM_MAP_PROVIDER);
      try (final var out = new PrintWriter(file.openWriter())) {
        for (final var provider : providers.keySet()) out.println(provider);
      }
    } catch (final IOException e) {
      processingEnv
        .getMessager()
        .printMessage(
          Diagnostic.Kind.ERROR,
          "Failed to write the @FromMap ServiceLoader registration: " + e.getMessage()
        );
    }
  }

  /**
   * A named module ignores {@code META-INF/services}: {@code ServiceLoader} finds a provider in one
   * only through a {@code provides} directive in its {@code module-info}. For each named module
   * whose binders it lacks, this says which line to write, listing every provider the module should
   * declare so the line replaces any {@code provides} it already has for the service.
   */
  private void reportUndeclaredModuleProviders() {
    final var byModule = new LinkedHashMap<ModuleElement, List<String>>();
    for (final var entry : providers.entrySet()) {
      final var module = processingEnv.getElementUtils().getModuleOf(entry.getValue());
      if (module == null || module.isUnnamed()) continue;
      byModule.computeIfAbsent(module, m -> new ArrayList<>()).add(entry.getKey().replace('$', '.'));
    }
    for (final var entry : byModule.entrySet()) {
      final var declared = declaredProviders(entry.getKey());
      if (declared.containsAll(entry.getValue())) continue;
      final var all = new LinkedHashSet<>(declared);
      all.addAll(entry.getValue());
      processingEnv
        .getMessager()
        .printMessage(
          Diagnostic.Kind.MANDATORY_WARNING,
          "@FromMap: module " +
            entry.getKey().getQualifiedName() +
            " does not provide every generated binder, and a named module is found by its provides" +
            " directives rather than META-INF/services, so a runtime Telescope.fromMap would refuse these" +
            " types as having no binder. Declare in module-info.java: provides " +
            FROM_MAP_PROVIDER +
            " with " +
            String.join(", ", all) +
            ";",
          entry.getKey()
        );
    }
  }

  /** The implementations {@code module} names in its {@code provides} for the binder SPI. */
  private static Set<String> declaredProviders(final ModuleElement module) {
    final var declared = new LinkedHashSet<String>();
    for (final var directive : ElementFilter.providesIn(module.getDirectives())) {
      if (!directive.getService().getQualifiedName().contentEquals(FROM_MAP_PROVIDER)) continue;
      for (final var implementation : directive.getImplementations()) {
        declared.add(implementation.getQualifiedName().toString());
      }
    }
    return declared;
  }

  /**
   * Map a target field type to the expression strategy that coerces a raw map value into it. The
   * shared spec decides the kind, and the runtime fromMap renders the same kind for the same type;
   * this method only writes each kind out as source.
   */
  private Coercion resolveCoercion(final TypeMirror type) {
    final var classified = MapValueTypes.classify(type, typeModel);
    final var fqn = boxedType(type);
    return switch (classified.kind()) {
      case SCALAR -> scalarCoercion(classified.scalar(), classified.primitive());
      case ENUM -> new Coercion.EnumOf(fqn);
      case NESTED -> new Coercion.Nested(fqn + "FromMap");
      case LIST -> new Coercion.Listed(resolveCoercion(argument(type, 0)));
      case SET -> new Coercion.Setted(resolveCoercion(argument(type, 0)));
      case OPTIONAL -> new Coercion.OptionalOf(resolveCoercion(argument(type, 0)));
      case MAP -> new Coercion.MapValues(resolveCoercion(argument(type, 0)), resolveCoercion(argument(type, 1)));
      case CAST, AS_IS -> new Coercion.Cast(fqn);
      case STRING_BUILT -> {
        final var factory = MapValueTypes.stringBuilt(typeModel.declaredName(type)).orElseThrow().factory();
        yield new Coercion.StringFactory(
          fqn,
          factory == null ? new Coercion.Factory.Ctor() : new Coercion.Factory.Static(factory)
        );
      }
      case COLLECTION_SUBTYPE -> new Coercion.Unsupported(
        fqn + " is a collection subtype — declare the field as List/Set/Map/Optional so @FromMap can build it"
      );
      case UNKNOWN_JDK -> new Coercion.Unsupported(
        fqn +
          " can't be built from a Map value by @FromMap — use the runtime Telescope.fromMap" +
          " with a custom extract(key, accessor, converter)"
      );
      case NO_BINDER -> new Coercion.Unsupported(
        fqn + " is a nested object but isn't @FromMap — annotate " + fqn + " with @FromMap"
      );
      case UNSUPPORTED -> new Coercion.Unsupported(
        type + " can't be coerced from a Map (type variable / array / unsupported kind)"
      );
    };
  }

  /**
   * A primitive, parsed from a {@code Number} or a {@code String}, or its wrapper, parsed the same
   * way but left {@code null} where the primitive takes its default.
   */
  private static Coercion scalarCoercion(final String primitive, final boolean unboxed) {
    return switch (primitive) {
      case "int" -> new Coercion.Parse("intValue", "java.lang.Integer.parseInt", unboxed ? "0" : "null");
      case "long" -> new Coercion.Parse("longValue", "java.lang.Long.parseLong", unboxed ? "0L" : "null");
      case "double" -> new Coercion.Parse("doubleValue", "java.lang.Double.parseDouble", unboxed ? "0.0d" : "null");
      case "float" -> new Coercion.Parse("floatValue", "java.lang.Float.parseFloat", unboxed ? "0.0f" : "null");
      case "short" -> new Coercion.Parse("shortValue", "java.lang.Short.parseShort", unboxed ? "(short) 0" : "null");
      case "byte" -> new Coercion.Parse("byteValue", "java.lang.Byte.parseByte", unboxed ? "(byte) 0" : "null");
      case "boolean" -> new Coercion.BoolParse(unboxed ? "false" : "null");
      case "char" -> new Coercion.CharParse(unboxed ? "'\\0'" : "null");
      default -> throw new IllegalStateException("not a primitive: " + primitive);
    };
  }

  private static TypeMirror argument(final TypeMirror type, final int index) {
    return ((DeclaredType) type).getTypeArguments().get(index);
  }

  /** The facts the shared classification reads, over {@code javax.lang.model} types. */
  private final MapValueTypes.TypeModel<TypeMirror> typeModel = new MapValueTypes.TypeModel<>() {
    @Override
    public String primitiveName(final TypeMirror type) {
      return type.getKind().isPrimitive() ? type.getKind().name().toLowerCase(Locale.ROOT) : null;
    }

    @Override
    public String declaredName(final TypeMirror type) {
      if (type.getKind() != TypeKind.DECLARED) return null;
      return ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
    }

    @Override
    public List<TypeMirror> typeArguments(final TypeMirror type) {
      return List.copyOf(((DeclaredType) type).getTypeArguments());
    }

    @Override
    public boolean isEnum(final TypeMirror type) {
      return ((DeclaredType) type).asElement().getKind() == ElementKind.ENUM;
    }

    @Override
    public boolean hasGeneratedBinder(final TypeMirror type) {
      final var element = (TypeElement) ((DeclaredType) type).asElement();
      return hasAnnotation(element, ANNOTATION) || FromMapProcessor.this.hasGeneratedBinder(element);
    }

    @Override
    public boolean isCollectionOrMap(final TypeMirror type) {
      final var declared = (DeclaredType) type;
      return assignableToRaw(declared, "java.util.Collection") || assignableToRaw(declared, "java.util.Map");
    }
  };

  /** Whether {@code type} is assignable to the raw type named {@code rawFqn}. */
  private boolean assignableToRaw(final DeclaredType type, final String rawFqn) {
    final var types = processingEnv.getTypeUtils();
    final var raw = processingEnv.getElementUtils().getTypeElement(rawFqn);
    return raw != null && types.isAssignable(types.erasure(type), types.erasure(raw.asType()));
  }
}
