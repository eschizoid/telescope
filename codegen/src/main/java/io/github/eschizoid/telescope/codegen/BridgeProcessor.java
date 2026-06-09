package io.github.eschizoid.telescope.codegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * Annotation processor for {@link io.github.eschizoid.telescope.annotations.Bridge}. For each
 * annotated source type (a record or a class), emits a sibling {@code <Source>Bridge} holding a
 * {@code public static final Telescope<Source, Target> BRIDGE}, where {@code Target} is the
 * annotation's {@code value()}.
 *
 * <p>Both sides may be records or POJOs. Each direction reads the opposite side's fields (a record
 * component {@code x()}, or a POJO getter {@code getX()} / {@code isX()}) and rebuilds the near
 * side by an auto-detected strategy: a record via its canonical constructor; a POJO via a public
 * constructor whose parameter names match the fields, then a static {@code builder()}, then a
 * no-arg constructor plus {@code setX} setters. Fields match by name and must form a bijection.
 *
 * <p>Guards (each a compile error): the source must be a top-level record/class; the target must be
 * a top-level record/class; and the two must expose the same field names with a usable strategy.
 */
@SupportedAnnotationTypes("io.github.eschizoid.telescope.annotations.Bridge")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class BridgeProcessor extends AbstractTelescopeProcessor {

  /**
   * Public no-arg constructor required by the {@link javax.annotation.processing.Processor} SPI.
   */
  public BridgeProcessor() {
    super();
  }

  private static final String ANNOTATION = "io.github.eschizoid.telescope.annotations.Bridge";

  // A named field on either side: a record component or a POJO getter-property, with its type.
  private record Field(String name, TypeMirror type) {}

  /**
   * Identity key for a source/target pair. Compared by erasure of the declared types so generic
   * containers like {@code List<Foo>} vs {@code List<Foo>} match across encounters within the same
   * processing run.
   */
  private record TypePair(String sourceFq, String targetFq) {}

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var anno = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
    if (anno == null) return false;
    final Deque<TypePair> pending = new ArrayDeque<>();
    final Set<TypePair> seen = new HashSet<>();
    final Set<TypePair> userDeclared = new HashSet<>();
    for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
      if (element.getKind() != ElementKind.RECORD && element.getKind() != ElementKind.CLASS) {
        error(element, "@Bridge is only supported on records and classes");
        continue;
      }
      if (element.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
        error(element, "@Bridge is only supported on top-level types");
        continue;
      }
      final var target = targetType(element);
      if (target == null || target.getKind() != TypeKind.DECLARED) {
        error(element, "@Bridge value must be a class or record type");
        continue;
      }
      final var targetEl = (TypeElement) ((DeclaredType) target).asElement();
      if (targetEl.getNestingKind() != NestingKind.TOP_LEVEL) {
        error(element, "@Bridge target must be a top-level type");
        continue;
      }
      final var pair = new TypePair(
        ((TypeElement) element).getQualifiedName().toString(),
        targetEl.getQualifiedName().toString()
      );
      userDeclared.add(pair);
      if (seen.add(pair)) pending.add(pair);
    }
    // Drain the queue, generating bridges. Each generate(...) call may discover sub-pairs and add
    // them to `pending` for recursive emission. The `seen` set guards against re-emission (cycle
    // safety + multiple parent bridges sharing the same sub-pair).
    while (!pending.isEmpty()) {
      final var pair = pending.poll();
      final var sourceEl = processingEnv.getElementUtils().getTypeElement(pair.sourceFq());
      final var targetEl = processingEnv.getElementUtils().getTypeElement(pair.targetFq());
      if (sourceEl == null || targetEl == null) continue;
      generate(sourceEl, targetEl, pending, seen, userDeclared);
    }
    return true;
  }

  private void generate(
    final TypeElement source,
    final TypeElement target,
    final Deque<TypePair> pending,
    final Set<TypePair> seen,
    final Set<TypePair> userDeclared
  ) {
    final var pkg = processingEnv.getElementUtils().getPackageOf(source).getQualifiedName().toString();
    final var sourceFq = source.getQualifiedName().toString();
    final var targetFq = target.getQualifiedName().toString();
    final var thisPair = new TypePair(sourceFq, targetFq);
    final var bridgeName = bridgeClassName(source, target, userDeclared.contains(thisPair));
    final var qualifiedBridge = pkg.isEmpty() ? bridgeName : pkg + "." + bridgeName;

    final var sourceFields = fieldsOf(source);
    final var targetFields = fieldsOf(target);
    if (!sameNames(source, sourceFields, target, targetFields)) return;

    // Build per-field "read expression" recipes: identity, sub-pair recursion, or container lift.
    // The reads need to know how to convert each source-field-value into the matching target-field-
    // value (and vice versa). Per-field decisions can also enqueue new TypePairs to emit.
    final var fieldPlans = planFields(source, target, sourceFields, targetFields, pending, seen, userDeclared);
    if (fieldPlans == null) return;

    final Function<String, String> readForward = name ->
      applyForward(fieldPlans.get(name), readExpr(source, "s", fieldByName(sourceFields, name)));
    final Function<String, String> readBackward = name ->
      applyBackward(fieldPlans.get(name), readExpr(target, "t", fieldByName(targetFields, name)));

    final var forwardBody = buildExpr(target, readForward, targetFields);
    if (forwardBody == null) return;
    final var backwardBody = buildExpr(source, readBackward, sourceFields);
    if (backwardBody == null) return;

    writeClass(
      qualifiedBridge,
      bridgeName,
      "Generated by telescope-codegen for @Bridge " +
        source.getSimpleName() +
        (userDeclared.contains(thisPair) ? "." : " (auto-generated for nested sub-pair " + targetFq + ")."),
      source,
      out -> {
        // Static forward / backward methods so child bridges can reference us by
        // `BridgeName::forward`
        // / `::backward` without going through Telescope.set() (which would require a dummy
        // source
        // value for the backward direction). The Telescope BRIDGE wraps both via from/to/using.
        out.println("  public static " + targetFq + " forward(final " + sourceFq + " s) {");
        emitMethodBody(out, forwardBody);
        out.println("  }");
        out.println();
        out.println("  public static " + sourceFq + " backward(final " + targetFq + " t) {");
        emitMethodBody(out, backwardBody);
        out.println("  }");
        out.println();
        out.println("  public static final Telescope<" + sourceFq + ", " + targetFq + "> BRIDGE =");
        out.println("    Telescope.from(" + sourceFq + ".class).to(" + targetFq + ".class).using(");
        out.println("      " + bridgeName + "::forward,");
        out.println("      " + bridgeName + "::backward);");
      }
    );
  }

  /**
   * Emit the method body for {@code body} returned from {@link #buildExpr}. {@code body} comes in
   * two shapes: an <em>expression</em> ({@code new Foo(...)}, {@code Foo.builder()...build()}) that
   * needs wrapping with {@code return ... ;}, or a <em>block</em> ({@code { final var out = ...;
   * ... return out; }}) that's already a statement list and just needs the braces stripped.
   */
  private static void emitMethodBody(final java.io.PrintWriter out, final String body) {
    if (body.startsWith("{")) {
      // Block form: "{ final var out = ...; ...; return out; }" — emit statements directly.
      // Strip outer braces and split on the existing semicolons + spaces. Indent each line.
      final var inner = body.substring(1, body.length() - 1).trim();
      for (final var stmt : inner.split("(?<=;) ")) {
        if (!stmt.isBlank()) out.println("    " + stmt.trim());
      }
    } else {
      out.println("    return " + body + ";");
    }
  }

  /**
   * Per-field conversion plan. Mirrors the runtime {@code DeepMap.autoIso} dispatch shape so
   * codegen has feature parity:
   *
   * <ul>
   *   <li>{@code IDENTITY} — types match exactly; pass the value through unchanged.
   *   <li>{@code RECURSE} — scalar sub-pair with both sides reflectable; reference its
   *       forward/backward methods directly.
   *   <li>{@code LIST}, {@code SET}, {@code MAP_VALUES}, {@code OPTIONAL} — same-kind container on
   *       both sides with element types that need a sub-bridge; lift element-wise via stream / map.
   *   <li>{@code OPTIONAL_TO_NULLABLE} — source is {@code Optional<X>}, target is plain (nullable)
   *       {@code Y}; bridge the element and unwrap on forward / wrap on backward.
   *   <li>{@code NULLABLE_TO_OPTIONAL} — mirror direction.
   * </ul>
   */
  private record FieldPlan(Kind kind, String subBridgeName) {
    enum Kind {
      IDENTITY,
      RECURSE,
      LIST,
      SET,
      MAP_VALUES,
      OPTIONAL,
      OPTIONAL_TO_NULLABLE,
      NULLABLE_TO_OPTIONAL,
    }

    static FieldPlan identity() {
      return new FieldPlan(Kind.IDENTITY, null);
    }

    static FieldPlan recurse(final String subBridgeName) {
      return new FieldPlan(Kind.RECURSE, Objects.requireNonNull(subBridgeName));
    }

    static FieldPlan ofKind(final Kind kind, final String subBridgeName) {
      return new FieldPlan(kind, Objects.requireNonNull(subBridgeName));
    }
  }

  /**
   * Container shape of a type — mirrors {@code DeepMap.ContainerShape}. {@code null} keyType for
   * non-Map shapes; the keyType on a Map is validated to match across the source/target pair so the
   * lift preserves keys identically.
   */
  private record ContainerShape(FieldPlan.Kind kind, TypeMirror elementType, TypeMirror keyType) {
    static ContainerShape of(final TypeMirror type) {
      if (!(type instanceof DeclaredType dt)) return null;
      final var el = dt.asElement();
      if (!(el instanceof TypeElement te)) return null;
      final var args = dt.getTypeArguments();
      return switch (te.getQualifiedName().toString()) {
        case "java.util.List" -> args.size() == 1 ? new ContainerShape(FieldPlan.Kind.LIST, args.getFirst(), null) : null;
        case "java.util.Set" -> args.size() == 1 ? new ContainerShape(FieldPlan.Kind.SET, args.getFirst(), null) : null;
        case "java.util.Optional" -> args.size() == 1
          ? new ContainerShape(FieldPlan.Kind.OPTIONAL, args.getFirst(), null)
          : null;
        case "java.util.Map" -> args.size() == 2
          ? new ContainerShape(FieldPlan.Kind.MAP_VALUES, args.get(1), args.get(0))
          : null;
        default -> null;
      };
    }
  }

  /**
   * For each named field pair (source.name has same name on both sides), decide how to convert.
   * Identity links pass the value through unchanged. Recursive links queue a sub-pair for emission
   * and reference its forward/backward methods. Returns {@code null} on a type mismatch that we
   * can't bridge — the caller skips emission for this pair.
   */
  private java.util.Map<String, FieldPlan> planFields(
    final TypeElement source,
    final TypeElement target,
    final List<Field> sourceFields,
    final List<Field> targetFields,
    final Deque<TypePair> pending,
    final Set<TypePair> seen,
    final Set<TypePair> userDeclared
  ) {
    final var plans = new java.util.LinkedHashMap<String, FieldPlan>();
    for (final var sf : sourceFields) {
      final var tf = fieldByName(targetFields, sf.name());
      // (1) Same type → identity. Covers same-typed containers too (List<X>↔List<X> is identity).
      if (isSameType(sf.type(), tf.type())) {
        plans.put(sf.name(), FieldPlan.identity());
        continue;
      }
      // (2) Container shape detection — both sides container of the same kind with element types
      //     that need their own sub-bridge. List/Set/Optional/Map values, key-equal Map.
      final var srcShape = ContainerShape.of(sf.type());
      final var tgtShape = ContainerShape.of(tf.type());
      if (srcShape != null && tgtShape != null && srcShape.kind() == tgtShape.kind()) {
        if (srcShape.kind() == FieldPlan.Kind.MAP_VALUES && !isSameType(srcShape.keyType(), tgtShape.keyType())) {
          error(
            source,
            "@Bridge " +
              source.getSimpleName() +
              " -> " +
              target.getSimpleName() +
              ": field '" +
              sf.name() +
              "' has incompatible Map key types — " +
              srcShape.keyType() +
              " vs " +
              tgtShape.keyType() +
              ". Map key types must match exactly; codegen preserves source keys."
          );
          return null;
        }
        final var subPlan = planElementSubBridge(
          source,
          target,
          sf.name(),
          srcShape.elementType(),
          tgtShape.elementType(),
          srcShape.kind(),
          pending,
          seen,
          userDeclared
        );
        if (subPlan == null) return null;
        plans.put(sf.name(), subPlan);
        continue;
      }
      // (3) Cross-paradigm Optional↔nullable bridge — one side has Optional<X>, the other has
      //     plain (possibly null) X. Element side must be reflectable to bridge.
      if (srcShape != null && srcShape.kind() == FieldPlan.Kind.OPTIONAL && tgtShape == null) {
        final var subPlan = planElementSubBridge(
          source,
          target,
          sf.name(),
          srcShape.elementType(),
          tf.type(),
          FieldPlan.Kind.OPTIONAL_TO_NULLABLE,
          pending,
          seen,
          userDeclared
        );
        if (subPlan == null) return null;
        plans.put(sf.name(), subPlan);
        continue;
      }
      if (tgtShape != null && tgtShape.kind() == FieldPlan.Kind.OPTIONAL && srcShape == null) {
        final var subPlan = planElementSubBridge(
          source,
          target,
          sf.name(),
          sf.type(),
          tgtShape.elementType(),
          FieldPlan.Kind.NULLABLE_TO_OPTIONAL,
          pending,
          seen,
          userDeclared
        );
        if (subPlan == null) return null;
        plans.put(sf.name(), subPlan);
        continue;
      }
      // (4) Both sides are scalar reflectable declared types → sub-bridge.
      if (
        sf.type() instanceof DeclaredType st &&
        tf.type() instanceof DeclaredType tt &&
        isReflectableDeclared(st) &&
        isReflectableDeclared(tt)
      ) {
        final var subSourceEl = (TypeElement) st.asElement();
        final var subTargetEl = (TypeElement) tt.asElement();
        final var subPair = new TypePair(
          subSourceEl.getQualifiedName().toString(),
          subTargetEl.getQualifiedName().toString()
        );
        if (seen.add(subPair)) pending.add(subPair);
        final var subBridgeName = bridgeClassName(subSourceEl, subTargetEl, userDeclared.contains(subPair));
        plans.put(sf.name(), FieldPlan.recurse(subBridgeName));
        continue;
      }
      error(
        source,
        "@Bridge " +
          source.getSimpleName() +
          " -> " +
          target.getSimpleName() +
          ": field '" +
          sf.name() +
          "' has incompatible types (" +
          sf.type() +
          " vs " +
          tf.type() +
          ") and no auto-bridge could be derived. Both sides must be records/classes telescope can " +
          "introspect (or the same type), or matching containers thereof."
      );
      return null;
    }
    return plans;
  }

  /**
   * Plan a sub-bridge for a container's element pair or a cross-paradigm Optional↔nullable pair.
   * Same-typed elements (when isSameType holds) still need a {@link FieldPlan} with the container
   * kind, so the lift-code path generates the right traversal — but the sub-bridge reference is
   * unused. Reflectable element pairs queue the sub-pair for emission.
   */
  private FieldPlan planElementSubBridge(
    final TypeElement parentSource,
    final TypeElement parentTarget,
    final String fieldName,
    final TypeMirror srcElement,
    final TypeMirror tgtElement,
    final FieldPlan.Kind kind,
    final Deque<TypePair> pending,
    final Set<TypePair> seen,
    final Set<TypePair> userDeclared
  ) {
    if (isSameType(srcElement, tgtElement)) {
      // Container kind matters (lift), but no sub-bridge — the element passes through. Use a
      // sentinel "IDENTITY" sub-bridge name; the emit code recognises it and skips the sub-call.
      return FieldPlan.ofKind(kind, IDENTITY_ELEMENT_SENTINEL);
    }
    if (
      srcElement instanceof DeclaredType sd &&
      tgtElement instanceof DeclaredType td &&
      isReflectableDeclared(sd) &&
      isReflectableDeclared(td)
    ) {
      final var subSourceEl = (TypeElement) sd.asElement();
      final var subTargetEl = (TypeElement) td.asElement();
      final var subPair = new TypePair(
        subSourceEl.getQualifiedName().toString(),
        subTargetEl.getQualifiedName().toString()
      );
      if (seen.add(subPair)) pending.add(subPair);
      final var subBridgeName = bridgeClassName(subSourceEl, subTargetEl, userDeclared.contains(subPair));
      return FieldPlan.ofKind(kind, subBridgeName);
    }
    error(
      parentSource,
      "@Bridge " +
        parentSource.getSimpleName() +
        " -> " +
        parentTarget.getSimpleName() +
        ": container field '" +
        fieldName +
        "' element types are incompatible (" +
        srcElement +
        " vs " +
        tgtElement +
        "). Element types must match exactly or both be records/classes telescope can introspect."
    );
    return null;
  }

  /** Sentinel sub-bridge name meaning "the element passes through unchanged". */
  private static final String IDENTITY_ELEMENT_SENTINEL = "__IDENTITY__";

  private String applyForward(final FieldPlan plan, final String readExpr) {
    final var sub = plan.subBridgeName();
    final boolean elementIdentity = IDENTITY_ELEMENT_SENTINEL.equals(sub);
    final var fwdElement = elementIdentity ? "e -> e" : sub + "::forward";
    return switch (plan.kind()) {
      case IDENTITY -> readExpr;
      case RECURSE -> sub + ".forward(" + readExpr + ")";
      case LIST -> readExpr + ".stream().map(" + fwdElement + ").toList()";
      case SET -> readExpr +
      ".stream().map(" +
      fwdElement +
      ").collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))";
      case OPTIONAL -> readExpr + ".map(" + fwdElement + ")";
      case MAP_VALUES -> readExpr +
      ".entrySet().stream().collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, e -> " +
      (elementIdentity ? "e.getValue()" : sub + ".forward(e.getValue())") +
      ", (a, b) -> a, java.util.LinkedHashMap::new))";
      case OPTIONAL_TO_NULLABLE -> readExpr + ".map(" + fwdElement + ").orElse(null)";
      case NULLABLE_TO_OPTIONAL -> "java.util.Optional.ofNullable(" + readExpr + ").map(" + fwdElement + ")";
    };
  }

  private String applyBackward(final FieldPlan plan, final String readExpr) {
    final var sub = plan.subBridgeName();
    final boolean elementIdentity = IDENTITY_ELEMENT_SENTINEL.equals(sub);
    final var bwdElement = elementIdentity ? "e -> e" : sub + "::backward";
    return switch (plan.kind()) {
      case IDENTITY -> readExpr;
      case RECURSE -> sub + ".backward(" + readExpr + ")";
      case LIST -> readExpr + ".stream().map(" + bwdElement + ").toList()";
      case SET -> readExpr +
      ".stream().map(" +
      bwdElement +
      ").collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))";
      case OPTIONAL -> readExpr + ".map(" + bwdElement + ")";
      case MAP_VALUES -> readExpr +
      ".entrySet().stream().collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, e -> " +
      (elementIdentity ? "e.getValue()" : sub + ".backward(e.getValue())") +
      ", (a, b) -> a, java.util.LinkedHashMap::new))";
      // For the cross-paradigm bridges, forward and backward are mirror images.
      case OPTIONAL_TO_NULLABLE -> "java.util.Optional.ofNullable(" + readExpr + ").map(" + bwdElement + ")";
      case NULLABLE_TO_OPTIONAL -> readExpr + ".map(" + bwdElement + ").orElse(null)";
    };
  }

  /**
   * Compute the bridge class name. User-declared pairs use the original convention {@code
   * <Source>Bridge}; auto-generated sub-pairs use {@code <Source>To<Target>Bridge} so they don't
   * collide with a user-declared {@code <Source>Bridge} that points at a different target.
   */
  private static String bridgeClassName(
    final TypeElement source,
    final TypeElement target,
    final boolean userDeclared
  ) {
    if (userDeclared) return source.getSimpleName() + "Bridge";
    return source.getSimpleName() + "To" + target.getSimpleName() + "Bridge";
  }

  /**
   * Whether two TypeMirrors refer to the same type by erasure (handles generics + raw equality).
   */
  private boolean isSameType(final TypeMirror a, final TypeMirror b) {
    return processingEnv.getTypeUtils().isSameType(a, b);
  }

  /** Whether the declared type is a record/class telescope can recurse into. */
  private boolean isReflectableDeclared(final DeclaredType dt) {
    final var el = dt.asElement();
    if (!(el instanceof TypeElement te)) return false;
    final var kind = te.getKind();
    if (kind != ElementKind.RECORD && kind != ElementKind.CLASS) return false;
    // Filter out boxed scalars / String / common JDK types we don't want to recurse into.
    final var fq = te.getQualifiedName().toString();
    return (
      !fq.startsWith("java.lang.") &&
      !fq.startsWith("java.time.") &&
      !fq.startsWith("java.util.") &&
      !fq.startsWith("java.math.")
    );
  }

  // Read field `f` from `var`: `var.f()` for a record, `var.getF()` / `var.isF()` for a POJO.
  private String readExpr(final TypeElement owner, final String var, final Field f) {
    if (owner.getKind() == ElementKind.RECORD) return var + "." + f.name() + "()";
    return var + "." + getterName(owner, f.name(), f.type()) + "()";
  }

  // Construct `to` from a name->expression reader: canonical ctor (record), or name-matched ctor /
  // builder / no-arg+setters (POJO). Returns an expression or a `{ ... return x; }` block; null on
  // failure (error reported on `to`).
  private String buildExpr(final TypeElement to, final Function<String, String> read, final List<Field> toFields) {
    final var toFq = to.getQualifiedName().toString();
    if (to.getKind() == ElementKind.RECORD) {
      final var args = to
        .getRecordComponents()
        .stream()
        .map(c -> read.apply(c.getSimpleName().toString()))
        .collect(Collectors.joining(", "));
      return "new " + toFq + "(" + args + ")";
    }

    // POJO: a public constructor whose parameter names match the fields (order-independent).
    for (final var ctor : ElementFilter.constructorsIn(to.getEnclosedElements())) {
      if (!ctor.getModifiers().contains(Modifier.PUBLIC) || ctor.getParameters().size() != toFields.size()) continue;
      final var args = new ArrayList<String>();
      var matched = true;
      for (final var p : ctor.getParameters()) {
        final var pn = p.getSimpleName().toString();
        if (!hasField(toFields, pn)) {
          matched = false;
          break;
        }
        args.add(read.apply(pn));
      }
      if (matched) return "new " + toFq + "(" + String.join(", ", args) + ")";
    }

    // POJO: a static builder() with a method per field.
    final var builder = staticBuilderMethod(to);
    if (builder != null && builder.getReturnType().getKind() == TypeKind.DECLARED) {
      final var builderType = (TypeElement) ((DeclaredType) builder.getReturnType()).asElement();
      final var sb = new StringBuilder(toFq + ".builder()");
      for (final var f : toFields) {
        final var method = builderSetter(builderType, f.name());
        if (method == null) {
          error(to, "@Bridge: builder " + builderType.getQualifiedName() + " has no method for '" + f.name() + "'");
          return null;
        }
        sb.append(".").append(method).append("(").append(read.apply(f.name())).append(")");
      }
      return sb.append(".build()").toString();
    }

    // POJO: a no-arg constructor plus a setter per field.
    if (hasPublicNoArgConstructor(to)) {
      final var sb = new StringBuilder("{ final var out = new " + toFq + "(); ");
      for (final var f : toFields) {
        final var setter = setterName(to, f.name());
        if (setter == null) {
          error(to, "@Bridge: " + toFq + " has a no-arg constructor but no setter for '" + f.name() + "'");
          return null;
        }
        sb.append("out.").append(setter).append("(").append(read.apply(f.name())).append("); ");
      }
      return sb.append("return out; }").toString();
    }

    error(
      to,
      "@Bridge: " +
        toFq +
        " has no usable construction strategy — needs a record canonical constructor, a constructor " +
        "whose parameter names match the fields, a static builder(), or a no-arg constructor with setters"
    );
    return null;
  }

  // The named fields of a type: record components, or POJO getter-properties (getX/isX), in order.
  // For the bean case, delegates to the inherited beanProperties(...) scan in
  // AbstractTelescopeProcessor — same algorithm, lives in one place. Drops the getter-name field
  // (this processor uses its own getterName(...) resolver below).
  private List<Field> fieldsOf(final TypeElement type) {
    if (type.getKind() == ElementKind.RECORD) {
      return type
        .getRecordComponents()
        .stream()
        .map(c -> new Field(c.getSimpleName().toString(), c.asType()))
        .toList();
    }
    return beanProperties(type)
      .stream()
      .map(p -> new Field(p.name(), p.type()))
      .toList();
  }

  private String getterName(final TypeElement pojo, final String field, final TypeMirror type) {
    final var cap = capitalize(field);
    final var isBoolean = type.getKind() == TypeKind.BOOLEAN || "java.lang.Boolean".equals(type.toString());
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(pojo))) {
      if (!isPublicInstance(m) || !m.getParameters().isEmpty() || m.getReturnType().getKind() == TypeKind.VOID) {
        continue;
      }
      final var name = m.getSimpleName().toString();
      if (name.equals("get" + cap) || (isBoolean && name.equals("is" + cap))) return name;
    }
    return null;
  }

  private boolean sameNames(
    final TypeElement source,
    final List<Field> sourceFields,
    final TypeElement target,
    final List<Field> targetFields
  ) {
    final var sn = sourceFields.stream().map(Field::name).collect(Collectors.toCollection(TreeSet::new));
    final var tn = targetFields.stream().map(Field::name).collect(Collectors.toCollection(TreeSet::new));
    if (sn.equals(tn)) return true;
    error(
      source,
      "@Bridge: " +
        source.getSimpleName() +
        " and " +
        target.getSimpleName() +
        " must expose the same field names (a bijection). " +
        source.getSimpleName() +
        " has " +
        sn +
        ", " +
        target.getSimpleName() +
        " has " +
        tn
    );
    return false;
  }

  private static Field fieldByName(final List<Field> fields, final String name) {
    for (final var f : fields) if (f.name().equals(name)) return f;
    throw new IllegalStateException("no field '" + name + "' (bijection should have guaranteed it)");
  }

  private static boolean hasField(final List<Field> fields, final String name) {
    for (final var f : fields) if (f.name().equals(name)) return true;
    return false;
  }

  private TypeMirror targetType(final Element element) {
    for (final var am : element.getAnnotationMirrors()) {
      if (!ANNOTATION.contentEquals(((TypeElement) am.getAnnotationType().asElement()).getQualifiedName())) continue;
      for (final var entry : am.getElementValues().entrySet()) {
        if (entry.getKey().getSimpleName().contentEquals("value")) {
          return (TypeMirror) entry.getValue().getValue();
        }
      }
    }
    return null;
  }
}
