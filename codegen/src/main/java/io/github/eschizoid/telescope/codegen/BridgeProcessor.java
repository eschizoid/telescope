package io.github.eschizoid.telescope.codegen;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
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
@SupportedAnnotationTypes(
  { "io.github.eschizoid.telescope.annotations.Bridge", "io.github.eschizoid.telescope.annotations.Bridges" }
)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class BridgeProcessor extends AbstractTelescopeProcessor {

  /**
   * Public no-arg constructor required by the {@link javax.annotation.processing.Processor} SPI.
   */
  public BridgeProcessor() {
    super();
  }

  private static final String ANNOTATION = "io.github.eschizoid.telescope.annotations.Bridge";
  private static final String BRIDGES_ANNOTATION = "io.github.eschizoid.telescope.annotations.Bridges";

  // A named field on either side: a record component or a POJO getter-property, with its type.
  private record Field(String name, TypeMirror type) {}

  /**
   * Identity key for a source/target pair. Compared by erasure of the declared types so generic
   * containers like {@code List<Foo>} vs {@code List<Foo>} match across encounters within the same
   * processing run.
   */
  private record TypePair(String sourceFq, String targetFq) {}

  // Source FQNs whose @Bridge appears more than once (via @Repeatable). The naming switches to the
  // long form `<Source>To<Target>Bridge` for ALL of their bridges to keep emitted class names
  // unambiguous. Populated in process(), read in generate() / generateSealed().
  private final Set<String> multiTargetSources = new HashSet<>();

  // Per-pair drops: source field names that are absent on the target by user declaration. Forward
  // skips them; backward fills the dropped slot with the type's zero value. Populated in process(),
  // read in generate().
  private final Map<TypePair, Set<String>> dropsByPair = new HashMap<>();

  // Per-pair renames: source field name -> primary target field name for fields whose two sides
  // have different names. The bijection check applies renames on the source side before comparing
  // to target names; planFields and the field-read expressions follow the mapping. For forward-only
  // fan-out (one source feeding multiple targets), this map holds the FIRST-declared target —
  // that's
  // the one backward reads from. Extra fan-out targets live in renameFanoutsByPair. Populated in
  // process(), read in generate().
  private final Map<TypePair, Map<String, String>> renamesByPair = new HashMap<>();

  // Per-pair forward-only fan-out extras: source field name -> ordered list of EXTRA target field
  // names beyond the primary in renamesByPair. Only present when @Rename(forwardOnly = true) is
  // used
  // on two or more renames sharing a source. Forward writes the source value to every target
  // (primary + extras); backward reads only the primary. Populated in process(), read in
  // generate().
  private final Map<TypePair, Map<String, List<String>>> renameFanoutsByPair = new HashMap<>();

  // Per-pair per-field transforms: source field name -> BridgeFn class FQN. The transformed field
  // is
  // routed through new <Class>().forward(...) / .backward(...) on each direction and is exempt from
  // the same-type bijection check. Populated in process(), read in generate().
  private final Map<TypePair, Map<String, String>> transformsByPair = new HashMap<>();

  // Per-pair set of source field names whose @Transform was declared with forwardOnly = true.
  // Backward direction emits a zero-value fill for these slots (the same shape `drops` uses) and
  // never invokes the user's BridgeFn.backward. Mirrors the runtime Mapping.forward(...) semantics.
  // Populated in process(), read in generate().
  private final Map<TypePair, Set<String>> forwardOnlyTransformsByPair = new HashMap<>();

  // Per-pair source-field defaults: source field name -> already-parsed Java-literal expression
  // (e.g. "\"EMEA\"", "42", "true"). Forward direction wraps the source read in (s.field == null
  // ? <literal> : s.field()) when an entry is present. Backward is identity. Mirrors the runtime
  // Mapping.toOrElse(srcAcc, tgtAcc, defaultValue) factory. Populated + validated in process().
  private final Map<TypePair, Map<String, String>> defaultsByPair = new HashMap<>();

  // Per-pair user-specified nested-bridge overrides: source field name -> bridge class FQN. The
  // referenced class must expose `public static T forward(S)` + `public static S backward(T)` at
  // signatures matching the field. When present, planFields uses FieldPlan.recurse(userBridge)
  // for the row instead of deriving / emitting a sub-bridge for the field's nested pair. Mirrors
  // the runtime Mapping.via(srcAcc, tgtAcc, mapper) factory.
  private final Map<TypePair, Map<String, String>> viaMappersByPair = new HashMap<>();

  // Per-pair user-specified write strategy override. AUTO (the default) preserves today's
  // ctor → builder → no-arg+setters ladder; other values force one strategy and surface a precise
  // error if the POJO doesn't support it. Mirrors the runtime WriteHint.writeBean(cls, strategy)
  // hint. Populated in process(), read in generate().
  private final Map<TypePair, String> writeStrategyByPair = new HashMap<>();

  // Per-pair constants: target field name -> the already-emitted Java literal expression
  // ("\"API\"", "true", "0L", "null", etc.). Forward-only — injected into the target ctor arg;
  // backward silently drops the slot. Populated in process(), validated + emitted in generate().
  private final Map<TypePair, Map<String, String>> constantsByPair = new HashMap<>();

  // Per-pair computes: target field name -> Supplier class FQN. The bridge emits one static
  // instance per computed field and calls .get() in the forward direction. Forward-only.
  private final Map<TypePair, Map<String, String>> computesByPair = new HashMap<>();

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var anno = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
    if (anno == null) return false;
    final var bridgesAnno = processingEnv.getElementUtils().getTypeElement(BRIDGES_ANNOTATION);
    // Reset per-run bookkeeping so a second processing round starts from a clean slate.
    multiTargetSources.clear();
    dropsByPair.clear();
    renamesByPair.clear();
    renameFanoutsByPair.clear();
    transformsByPair.clear();
    forwardOnlyTransformsByPair.clear();
    defaultsByPair.clear();
    viaMappersByPair.clear();
    writeStrategyByPair.clear();
    constantsByPair.clear();
    computesByPair.clear();

    final Deque<TypePair> pending = new ArrayDeque<>();
    final Set<TypePair> seen = new HashSet<>();
    final Set<TypePair> userDeclared = new HashSet<>();

    // Collect annotated elements from both @Bridge (single use) and @Bridges (the container that
    // javac wraps multiple @Bridge into when the user declares more than one on the same type).
    final var elements = new LinkedHashSet<Element>();
    elements.addAll(roundEnv.getElementsAnnotatedWith(anno));
    if (bridgesAnno != null) elements.addAll(roundEnv.getElementsAnnotatedWith(bridgesAnno));

    for (final var element : elements) {
      final var kind = element.getKind();
      final var isSealedInterface = kind == ElementKind.INTERFACE && element.getModifiers().contains(Modifier.SEALED);
      if (kind != ElementKind.RECORD && kind != ElementKind.CLASS && !isSealedInterface) {
        error(element, "@Bridge is only supported on records, classes, or sealed interfaces");
        continue;
      }
      if (element.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
        error(element, "@Bridge is only supported on top-level types");
        continue;
      }
      final var sourceFq = ((TypeElement) element).getQualifiedName().toString();
      final var bridges = collectBridgeAnnotations(element);
      if (bridges.isEmpty()) continue;
      if (bridges.size() > 1) multiTargetSources.add(sourceFq);

      for (final var bridgeAm : bridges) {
        final var target = targetTypeFromMirror(bridgeAm);
        if (target == null || target.getKind() != TypeKind.DECLARED) {
          error(element, "@Bridge value must be a class, record, or sealed-interface type");
          continue;
        }
        final var targetEl = (TypeElement) ((DeclaredType) target).asElement();
        if (targetEl.getNestingKind() != NestingKind.TOP_LEVEL) {
          error(element, "@Bridge target must be a top-level type");
          continue;
        }
        final var pair = new TypePair(sourceFq, targetEl.getQualifiedName().toString());
        userDeclared.add(pair);
        final var drops = dropsFromMirror(bridgeAm);
        if (!drops.isEmpty()) dropsByPair.put(pair, drops);
        final var renameSet = renamesFromMirror(element, bridgeAm);
        if (renameSet == null) continue; // invalid rename — error already reported, skip this pair
        if (!renameSet.bySource().isEmpty()) renamesByPair.put(pair, renameSet.bySource());
        if (!renameSet.fanoutExtras().isEmpty()) renameFanoutsByPair.put(pair, renameSet.fanoutExtras());
        final var transformSet = transformsFromMirror(element, bridgeAm);
        if (transformSet == null) continue; // invalid transform — already reported, skip this pair
        if (!transformSet.byField().isEmpty()) transformsByPair.put(pair, transformSet.byField());
        if (!transformSet.forwardOnlyFields().isEmpty()) forwardOnlyTransformsByPair.put(
          pair,
          transformSet.forwardOnlyFields()
        );
        final var constants = constantsFromMirror(element, bridgeAm);
        if (constants == null) continue; // invalid constant — already reported, skip this pair
        if (!constants.isEmpty()) constantsByPair.put(pair, constants);
        final var computes = computesFromMirror(element, bridgeAm);
        if (computes == null) continue; // invalid compute — already reported, skip this pair
        if (!computes.isEmpty()) computesByPair.put(pair, computes);
        final var rawDefaults = defaultsFromMirror(element, bridgeAm);
        if (rawDefaults == null) continue; // invalid default — already reported, skip this pair
        if (!rawDefaults.isEmpty()) defaultsByPair.put(pair, rawDefaults);
        final var viaMappers = viaMappersFromMirror(element, bridgeAm);
        if (viaMappers == null) continue; // invalid viaMapper — already reported, skip this pair
        if (!viaMappers.isEmpty()) viaMappersByPair.put(pair, viaMappers);
        final var writeStrategy = writeStrategyFromMirror(bridgeAm);
        if (writeStrategy != null && !"AUTO".equals(writeStrategy)) writeStrategyByPair.put(pair, writeStrategy);
        if (seen.add(pair)) pending.add(pair);
      }
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

  // Collect all @Bridge annotation mirrors on an element, transparently unwrapping the @Bridges
  // container that javac synthesises when the user declares @Bridge more than once.
  private List<AnnotationMirror> collectBridgeAnnotations(final Element element) {
    final var bridges = new ArrayList<AnnotationMirror>();
    for (final var am : element.getAnnotationMirrors()) {
      final var name = ((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().toString();
      if (name.equals(ANNOTATION)) {
        bridges.add(am);
      } else if (name.equals(BRIDGES_ANNOTATION)) {
        for (final var entry : am.getElementValues().entrySet()) {
          if (entry.getKey().getSimpleName().contentEquals("value")) {
            @SuppressWarnings("unchecked")
            final var list = (List<? extends AnnotationValue>) entry.getValue().getValue();
            for (final var av : list) bridges.add((AnnotationMirror) av.getValue());
          }
        }
      }
    }
    return bridges;
  }

  private TypeMirror targetTypeFromMirror(final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals("value")) return (TypeMirror) entry.getValue().getValue();
    }
    return null;
  }

  private Set<String> dropsFromMirror(final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals("drops")) {
        @SuppressWarnings("unchecked")
        final var list = (List<? extends AnnotationValue>) entry.getValue().getValue();
        final var result = new LinkedHashSet<String>();
        for (final var av : list) result.add((String) av.getValue());
        return result;
      }
    }
    return Set.of();
  }

  // Result of parsing a @Bridge's renames list. `bySource` holds the first-declared target per
  // source (the slot that backward direction reads from). `fanoutExtras` holds the extra targets
  // declared via @Rename(forwardOnly = true) sharing the same source — those targets receive the
  // same source value during forward emission but are invisible to backward. Empty fanoutExtras
  // means no fan-out was declared.
  private record RenameSet(Map<String, String> bySource, Map<String, List<String>> fanoutExtras) {
    static RenameSet empty() {
      return new RenameSet(Map.of(), Map.of());
    }
  }

  // Read renames from a @Bridge mirror. Returns null when a rename is malformed (already reported
  // via error()) so the caller can skip the pair; returns an empty RenameSet when no renames are
  // present. Forward-only fan-out: when two renames share a source and BOTH set forwardOnly = true,
  // the second target lands in fanoutExtras. A source-collision with any rename not flagged
  // forwardOnly is still an error — the user must opt in on every conflicting entry.
  private RenameSet renamesFromMirror(final Element element, final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (!entry.getKey().getSimpleName().contentEquals("renames")) continue;
      @SuppressWarnings("unchecked")
      final var list = (List<? extends AnnotationValue>) entry.getValue().getValue();
      final var primary = new LinkedHashMap<String, String>();
      final var fanoutExtras = new LinkedHashMap<String, List<String>>();
      final var seenTargets = new HashSet<String>();
      final var sourceForwardOnly = new HashMap<String, Boolean>();
      for (final var av : list) {
        final var renameAm = (AnnotationMirror) av.getValue();
        String src = null;
        String tgt = null;
        Boolean forwardOnly = Boolean.FALSE;
        for (final var re : renameAm.getElementValues().entrySet()) {
          final var k = re.getKey().getSimpleName().toString();
          if (k.equals("source")) src = (String) re.getValue().getValue();
          else if (k.equals("target")) tgt = (String) re.getValue().getValue();
          else if (k.equals("forwardOnly")) forwardOnly = (Boolean) re.getValue().getValue();
        }
        if (src == null || tgt == null || src.isEmpty() || tgt.isEmpty()) {
          error(element, "@Rename requires both `source` and `target` field names");
          return null;
        }
        if (!seenTargets.add(tgt)) {
          error(element, "@Rename target \"" + tgt + "\" appears twice in the renames list");
          return null;
        }
        if (primary.containsKey(src)) {
          if (!Boolean.TRUE.equals(forwardOnly) || !Boolean.TRUE.equals(sourceForwardOnly.get(src))) {
            error(
              element,
              "@Rename source \"" +
                src +
                "\" appears twice in the renames list — set forwardOnly = true on every conflicting" +
                " entry to opt into forward-only fan-out"
            );
            return null;
          }
          fanoutExtras.computeIfAbsent(src, __ -> new ArrayList<>()).add(tgt);
        } else {
          primary.put(src, tgt);
          sourceForwardOnly.put(src, forwardOnly);
        }
      }
      return new RenameSet(primary, fanoutExtras);
    }
    return RenameSet.empty();
  }

  // Result of parsing a @Bridge's transforms list. `byField` carries the source-field → BridgeFn
  // class FQN map (the long-standing shape); `forwardOnlyFields` carries the subset of source
  // field names whose @Transform was declared with forwardOnly = true.
  private record TransformSet(Map<String, String> byField, Set<String> forwardOnlyFields) {
    static TransformSet empty() {
      return new TransformSet(Map.of(), Set.of());
    }
  }

  // Read transforms from a @Bridge mirror. Returns null on a malformed transform (already reported)
  // so the caller can skip the pair. Empty TransformSet when no transforms are present.
  private TransformSet transformsFromMirror(final Element element, final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (!entry.getKey().getSimpleName().contentEquals("transforms")) continue;
      @SuppressWarnings("unchecked")
      final var list = (List<? extends AnnotationValue>) entry.getValue().getValue();
      final var result = new LinkedHashMap<String, String>();
      final var forwardOnlySet = new LinkedHashSet<String>();
      for (final var av : list) {
        final var transformAm = (AnnotationMirror) av.getValue();
        String field = null;
        TypeMirror using = null;
        Boolean forwardOnly = Boolean.FALSE;
        for (final var te : transformAm.getElementValues().entrySet()) {
          final var k = te.getKey().getSimpleName().toString();
          if (k.equals("field")) field = (String) te.getValue().getValue();
          else if (k.equals("using")) using = (TypeMirror) te.getValue().getValue();
          else if (k.equals("forwardOnly")) forwardOnly = (Boolean) te.getValue().getValue();
        }
        if (field == null || field.isEmpty()) {
          error(element, "@Transform requires a non-empty `field` name");
          return null;
        }
        if (using == null || using.getKind() != TypeKind.DECLARED) {
          error(element, "@Transform `using` must be a class implementing BridgeFn");
          return null;
        }
        final var usingEl = (TypeElement) ((DeclaredType) using).asElement();
        if (usingEl.getNestingKind() != NestingKind.TOP_LEVEL) {
          error(element, "@Transform `using` must be a top-level class");
          return null;
        }
        if (result.containsKey(field)) {
          error(element, "@Transform field=\"" + field + "\" is declared more than once");
          return null;
        }
        result.put(field, usingEl.getQualifiedName().toString());
        if (Boolean.TRUE.equals(forwardOnly)) forwardOnlySet.add(field);
      }
      return new TransformSet(result, forwardOnlySet);
    }
    return TransformSet.empty();
  }

  // Read writeStrategy from a @Bridge mirror. Returns the enum name (AUTO / CONSTRUCTOR / BUILDER
  // / SETTERS) when explicitly set; null when the user didn't supply a value (treated as AUTO).
  private String writeStrategyFromMirror(final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (!entry.getKey().getSimpleName().contentEquals("writeStrategy")) continue;
      final var val = entry.getValue().getValue();
      if (val instanceof javax.lang.model.element.VariableElement ve) return ve.getSimpleName().toString();
      return val == null ? null : val.toString();
    }
    return null;
  }

  // Read viaMappers from a @Bridge mirror. Returns source-field-name -> bridge-class FQN. The
  // referenced class's signatures are validated at the generated code level — if the static
  // forward/backward methods don't exist or don't match the field types, javac surfaces the error
  // at the generated bridge body. Returns null on a structural error (already reported).
  private Map<String, String> viaMappersFromMirror(final Element element, final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (!entry.getKey().getSimpleName().contentEquals("viaMappers")) continue;
      @SuppressWarnings("unchecked")
      final var list = (List<? extends AnnotationValue>) entry.getValue().getValue();
      final var result = new LinkedHashMap<String, String>();
      for (final var av : list) {
        final var viaAm = (AnnotationMirror) av.getValue();
        String field = null;
        TypeMirror using = null;
        for (final var ve : viaAm.getElementValues().entrySet()) {
          final var k = ve.getKey().getSimpleName().toString();
          if (k.equals("field")) field = (String) ve.getValue().getValue();
          else if (k.equals("using")) using = (TypeMirror) ve.getValue().getValue();
        }
        if (field == null || field.isEmpty()) {
          error(element, "@ViaMapper requires a non-empty `field` name");
          return null;
        }
        if (using == null || using.getKind() != TypeKind.DECLARED) {
          error(element, "@ViaMapper `using` must be a class (typically a generated <X>Bridge)");
          return null;
        }
        final var usingEl = (TypeElement) ((DeclaredType) using).asElement();
        if (usingEl.getNestingKind() != NestingKind.TOP_LEVEL) {
          error(element, "@ViaMapper `using` must be a top-level class");
          return null;
        }
        if (result.containsKey(field)) {
          error(element, "@ViaMapper field=\"" + field + "\" is declared more than once");
          return null;
        }
        result.put(field, usingEl.getQualifiedName().toString());
      }
      return result;
    }
    return Map.of();
  }

  // Read defaults from a @Bridge mirror. Returns the raw source-field-name -> raw-string-value
  // map. Per-pair validation (field exists on source, type is reference-typed, value parses
  // against the field type) happens in generate() where the source TypeElement is available.
  // Returns null on a structural error (already reported).
  private Map<String, String> defaultsFromMirror(final Element element, final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (!entry.getKey().getSimpleName().contentEquals("defaults")) continue;
      @SuppressWarnings("unchecked")
      final var list = (List<? extends AnnotationValue>) entry.getValue().getValue();
      final var result = new LinkedHashMap<String, String>();
      for (final var av : list) {
        final var defAm = (AnnotationMirror) av.getValue();
        String field = null;
        String value = null;
        for (final var de : defAm.getElementValues().entrySet()) {
          final var k = de.getKey().getSimpleName().toString();
          if (k.equals("field")) field = (String) de.getValue().getValue();
          else if (k.equals("value")) value = (String) de.getValue().getValue();
        }
        if (field == null || field.isEmpty()) {
          error(element, "@Default requires a non-empty `field` name");
          return null;
        }
        if (value == null) {
          error(element, "@Default requires a `value`");
          return null;
        }
        if (result.containsKey(field)) {
          error(element, "@Default field=\"" + field + "\" is declared more than once");
          return null;
        }
        result.put(field, value);
      }
      return result;
    }
    return Map.of();
  }

  // Read constants from a @Bridge mirror. Returns the raw target-field-name -> raw-string-value
  // map. Per-pair validation (field exists on target, value parses against the field type) happens
  // in generate() where the target TypeElement is available. Returns null on a structural error
  // (already reported).
  private Map<String, String> constantsFromMirror(final Element element, final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (!entry.getKey().getSimpleName().contentEquals("constants")) continue;
      @SuppressWarnings("unchecked")
      final var list = (List<? extends AnnotationValue>) entry.getValue().getValue();
      final var result = new LinkedHashMap<String, String>();
      for (final var av : list) {
        final var constAm = (AnnotationMirror) av.getValue();
        String field = null;
        String value = null;
        for (final var ce : constAm.getElementValues().entrySet()) {
          final var k = ce.getKey().getSimpleName().toString();
          if (k.equals("field")) field = (String) ce.getValue().getValue();
          else if (k.equals("value")) value = (String) ce.getValue().getValue();
        }
        if (field == null || field.isEmpty()) {
          error(element, "@Constant requires a non-empty `field` name");
          return null;
        }
        if (value == null) {
          error(element, "@Constant requires a `value`");
          return null;
        }
        if (result.containsKey(field)) {
          error(element, "@Constant field=\"" + field + "\" is declared more than once");
          return null;
        }
        result.put(field, value);
      }
      return result;
    }
    return Map.of();
  }

  // Read computes from a @Bridge mirror. Target field name -> Supplier class FQN.
  private Map<String, String> computesFromMirror(final Element element, final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (!entry.getKey().getSimpleName().contentEquals("computes")) continue;
      @SuppressWarnings("unchecked")
      final var list = (List<? extends AnnotationValue>) entry.getValue().getValue();
      final var result = new LinkedHashMap<String, String>();
      for (final var av : list) {
        final var compAm = (AnnotationMirror) av.getValue();
        String field = null;
        TypeMirror using = null;
        for (final var ce : compAm.getElementValues().entrySet()) {
          final var k = ce.getKey().getSimpleName().toString();
          if (k.equals("field")) field = (String) ce.getValue().getValue();
          else if (k.equals("using")) using = (TypeMirror) ce.getValue().getValue();
        }
        if (field == null || field.isEmpty()) {
          error(element, "@Compute requires a non-empty `field` name");
          return null;
        }
        if (using == null || using.getKind() != TypeKind.DECLARED) {
          error(element, "@Compute `using` must be a class implementing Supplier");
          return null;
        }
        final var usingEl = (TypeElement) ((DeclaredType) using).asElement();
        if (usingEl.getNestingKind() != NestingKind.TOP_LEVEL) {
          error(element, "@Compute `using` must be a top-level class");
          return null;
        }
        if (result.containsKey(field)) {
          error(element, "@Compute field=\"" + field + "\" is declared more than once");
          return null;
        }
        result.put(field, usingEl.getQualifiedName().toString());
      }
      return result;
    }
    return Map.of();
  }

  // Parse a @Constant string value against the target field's declared type. Returns the
  // Java-source literal expression to emit at the field's ctor-arg position, or null when the
  // value can't be represented at that type (the caller already reported via error()).
  private String parseConstantLiteral(
    final Element origin,
    final String fieldName,
    final String value,
    final TypeMirror type
  ) {
    final var kind = type.getKind();
    if (kind == TypeKind.DECLARED) {
      final var fqn = ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
      if ("null".equals(value)) return "null";
      if (fqn.equals("java.lang.String")) return "\"" + escapeJavaString(value) + "\"";
      if (fqn.equals("java.lang.Boolean")) return parseBooleanOrError(origin, fieldName, value, "Boolean");
      if (fqn.equals("java.lang.Integer")) return parseIntegralOrError(origin, fieldName, value, "Integer", "");
      if (fqn.equals("java.lang.Long")) return parseIntegralOrError(origin, fieldName, value, "Long", "L");
      if (fqn.equals("java.lang.Short")) return castIntegralOrError(origin, fieldName, value, "Short", "short");
      if (fqn.equals("java.lang.Byte")) return castIntegralOrError(origin, fieldName, value, "Byte", "byte");
      if (fqn.equals("java.lang.Double")) return parseFloatingOrError(origin, fieldName, value, "Double", "");
      if (fqn.equals("java.lang.Float")) return parseFloatingOrError(origin, fieldName, value, "Float", "f");
      if (fqn.equals("java.lang.Character")) return parseCharOrError(origin, fieldName, value);
    }
    if (kind.isPrimitive()) {
      return switch (kind) {
        case BOOLEAN -> parseBooleanOrError(origin, fieldName, value, "boolean");
        case INT -> parseIntegralOrError(origin, fieldName, value, "int", "");
        case LONG -> parseIntegralOrError(origin, fieldName, value, "long", "L");
        case SHORT -> castIntegralOrError(origin, fieldName, value, "short", "short");
        case BYTE -> castIntegralOrError(origin, fieldName, value, "byte", "byte");
        case DOUBLE -> parseFloatingOrError(origin, fieldName, value, "double", "");
        case FLOAT -> parseFloatingOrError(origin, fieldName, value, "float", "f");
        case CHAR -> parseCharOrError(origin, fieldName, value);
        default -> null;
      };
    }
    error(
      origin,
      "@Constant value cannot be parsed at the target field \"" +
        fieldName +
        "\" of type " +
        type +
        " — supported types are String, primitives and their boxed equivalents, and the literal \"null\" for reference types"
    );
    return null;
  }

  private String parseBooleanOrError(
    final Element origin,
    final String fieldName,
    final String value,
    final String displayType
  ) {
    if ("true".equals(value)) return "true";
    if ("false".equals(value)) return "false";
    error(
      origin,
      "@Constant value=\"" + value + "\" is not a valid " + displayType + " literal at field \"" + fieldName + "\""
    );
    return null;
  }

  private String parseIntegralOrError(
    final Element origin,
    final String fieldName,
    final String value,
    final String displayType,
    final String suffix
  ) {
    try {
      if ("int".equals(displayType) || "Integer".equals(displayType)) Integer.parseInt(value);
      else Long.parseLong(value);
    } catch (final NumberFormatException e) {
      error(
        origin,
        "@Constant value=\"" + value + "\" is not a valid " + displayType + " literal at field \"" + fieldName + "\""
      );
      return null;
    }
    return value + suffix;
  }

  private String castIntegralOrError(
    final Element origin,
    final String fieldName,
    final String value,
    final String displayType,
    final String cast
  ) {
    try {
      if ("short".equals(cast)) Short.parseShort(value);
      else Byte.parseByte(value);
    } catch (final NumberFormatException e) {
      error(
        origin,
        "@Constant value=\"" + value + "\" is not a valid " + displayType + " literal at field \"" + fieldName + "\""
      );
      return null;
    }
    return "(" + cast + ") " + value;
  }

  private String parseFloatingOrError(
    final Element origin,
    final String fieldName,
    final String value,
    final String displayType,
    final String suffix
  ) {
    try {
      if ("float".equals(displayType) || "Float".equals(displayType)) Float.parseFloat(value);
      else Double.parseDouble(value);
    } catch (final NumberFormatException e) {
      error(
        origin,
        "@Constant value=\"" + value + "\" is not a valid " + displayType + " literal at field \"" + fieldName + "\""
      );
      return null;
    }
    return value + suffix;
  }

  private String parseCharOrError(final Element origin, final String fieldName, final String value) {
    if (value.length() != 1) {
      error(origin, "@Constant value=\"" + value + "\" must be a single character at field \"" + fieldName + "\"");
      return null;
    }
    final var c = value.charAt(0);
    final var escaped = switch (c) {
      case '\\' -> "\\\\";
      case '\'' -> "\\'";
      case '\n' -> "\\n";
      case '\t' -> "\\t";
      case '\r' -> "\\r";
      default -> String.valueOf(c);
    };
    return "'" + escaped + "'";
  }

  private static String escapeJavaString(final String s) {
    final var b = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      final var c = s.charAt(i);
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"' -> b.append("\\\"");
        case '\n' -> b.append("\\n");
        case '\t' -> b.append("\\t");
        case '\r' -> b.append("\\r");
        default -> b.append(c);
      }
    }
    return b.toString();
  }

  private static String defaultLiteralFor(final TypeMirror type) {
    return switch (type.getKind()) {
      case BOOLEAN -> "false";
      case CHAR -> "'\\0'";
      case BYTE, SHORT, INT, LONG -> "0";
      case FLOAT -> "0.0f";
      case DOUBLE -> "0.0";
      default -> "null";
    };
  }

  private void generate(
    final TypeElement source,
    final TypeElement target,
    final Deque<TypePair> pending,
    final Set<TypePair> seen,
    final Set<TypePair> userDeclared
  ) {
    if (source.getKind() == ElementKind.INTERFACE) {
      generateSealed(source, target, pending, seen, userDeclared);
      return;
    }
    final var pkg = processingEnv.getElementUtils().getPackageOf(source).getQualifiedName().toString();
    final var sourceFq = source.getQualifiedName().toString();
    final var targetFq = target.getQualifiedName().toString();
    final var thisPair = new TypePair(sourceFq, targetFq);
    final var useShortName = userDeclared.contains(thisPair) && !multiTargetSources.contains(sourceFq);
    final var bridgeName = bridgeClassName(source, target, useShortName);
    final var qualifiedBridge = pkg.isEmpty() ? bridgeName : pkg + "." + bridgeName;

    final var sourceFields = fieldsOf(source);
    final var targetFields = fieldsOf(target);
    final var drops = dropsByPair.getOrDefault(thisPair, Set.of());
    final var renames = renamesByPair.getOrDefault(thisPair, Map.of());
    final var renameFanouts = renameFanoutsByPair.getOrDefault(thisPair, Map.of());
    final var transforms = transformsByPair.getOrDefault(thisPair, Map.of());
    final var forwardOnlyTransforms = forwardOnlyTransformsByPair.getOrDefault(thisPair, Set.of());
    final var rawDefaults = defaultsByPair.getOrDefault(thisPair, Map.of());
    final var viaMappers = viaMappersByPair.getOrDefault(thisPair, Map.of());
    final var writeStrategy = writeStrategyByPair.getOrDefault(thisPair, "AUTO");
    final var rawConstants = constantsByPair.getOrDefault(thisPair, Map.of());
    final var computes = computesByPair.getOrDefault(thisPair, Map.of());

    // Validate every transform field is a real source field (drops would mask the validation).
    for (final var t : transforms.keySet()) {
      if (!sourceFields.stream().anyMatch(f -> f.name().equals(t))) {
        error(
          source,
          "@Bridge transforms field=\"" +
            t +
            "\" is not a field of " +
            source.getSimpleName() +
            " — known fields: " +
            sourceFields.stream().map(Field::name).collect(Collectors.toSet())
        );
        return;
      }
      if (drops.contains(t)) {
        error(source, "@Bridge field \"" + t + "\" appears in both transforms and drops — pick one.");
        return;
      }
    }

    // Validate viaMappers fields exist and don't overlap with drops, transforms, renames, or
    // defaults. VM1 (renames overlap) and VM2 (defaults overlap) from the round-2 review: each
    // combination produces structurally valid emission with undefined or wrong semantics —
    // rename-overlap dispatches via the user-named bridge on the renamed-source-name slot;
    // default-overlap passes a literal-typed value to the bridge expecting the source type. Both
    // are silent-wrong paths until the user hits the generated-file diagnostic — too late.
    for (final var v : viaMappers.keySet()) {
      if (!sourceFields.stream().anyMatch(f -> f.name().equals(v))) {
        error(
          source,
          "@Bridge viaMappers field=\"" +
            v +
            "\" is not a field of " +
            source.getSimpleName() +
            " — known fields: " +
            sourceFields.stream().map(Field::name).collect(Collectors.toSet())
        );
        return;
      }
      if (drops.contains(v)) {
        error(source, "@Bridge field \"" + v + "\" appears in both viaMappers and drops — pick one.");
        return;
      }
      if (transforms.containsKey(v)) {
        error(source, "@Bridge field \"" + v + "\" appears in both viaMappers and transforms — pick one.");
        return;
      }
      if (renames.containsKey(v)) {
        error(source, "@Bridge field \"" + v + "\" appears in both viaMappers and renames — pick one.");
        return;
      }
      if (rawDefaults.containsKey(v)) {
        error(source, "@Bridge field \"" + v + "\" appears in both viaMappers and defaults — pick one.");
        return;
      }
    }

    // Validate every drop name actually names a source field. A misspelled or non-existent drop
    // is a precise compile error rather than a silent no-op.
    final var sourceNames = sourceFields.stream().map(Field::name).collect(Collectors.toSet());
    for (final var d : drops) {
      if (!sourceNames.contains(d)) {
        error(
          source,
          "@Bridge drops=\"" + d + "\" is not a field of " + source.getSimpleName() + " — known fields: " + sourceNames
        );
        return;
      }
    }

    // Validate renames: source side must exist on the source, target side on the target. A renamed
    // source cannot also be a drop (it would be redundant; the user picks one).
    final var targetNames = targetFields.stream().map(Field::name).collect(Collectors.toSet());
    for (final var e : renames.entrySet()) {
      if (!sourceNames.contains(e.getKey())) {
        error(
          source,
          "@Bridge renames source=\"" +
            e.getKey() +
            "\" is not a field of " +
            source.getSimpleName() +
            " — known fields: " +
            sourceNames
        );
        return;
      }
      if (!targetNames.contains(e.getValue())) {
        error(
          source,
          "@Bridge renames target=\"" +
            e.getValue() +
            "\" is not a field of " +
            target.getSimpleName() +
            " — known fields: " +
            targetNames
        );
        return;
      }
      if (drops.contains(e.getKey())) {
        error(
          source,
          "@Bridge field \"" + e.getKey() + "\" appears in both renames and drops — pick one."
        );
        return;
      }
      // OL-1: renames + transforms on the same source field is undefined behavior. The transform
      // dispatches on the source field name; the rename relabels the TARGET slot. Together they
      // accidentally compile to correct code in scalar cases but the combination has no documented
      // contract — reject explicitly so the user picks one.
      if (transforms.containsKey(e.getKey())) {
        error(
          source,
          "@Bridge field \"" +
            e.getKey() +
            "\" appears in both renames and transforms — pick one (the transform consumes the source field; the target slot name comes from the bijection, not @Rename)."
        );
        return;
      }
    }
    // Validate forward-only fan-out extras: every extra target must exist on the target side, each
    // extra target's type must match the primary target's type (forward writes one source value
    // into
    // every target slot — they must be assignment-compatible), and the extra target can't double up
    // with a constant/compute injection. seenTargets across the whole rename set is already
    // enforced
    // upstream in renamesFromMirror; this loop adds the source-side existence + type checks.
    for (final var fanout : renameFanouts.entrySet()) {
      final var srcName = fanout.getKey();
      final var primaryTgt = renames.get(srcName);
      final var primaryType = fieldByName(targetFields, primaryTgt).type();
      for (final var extraTgt : fanout.getValue()) {
        if (!targetNames.contains(extraTgt)) {
          error(
            source,
            "@Bridge renames target=\"" +
              extraTgt +
              "\" (forwardOnly fan-out from \"" +
              srcName +
              "\") is not a field of " +
              target.getSimpleName() +
              " — known fields: " +
              targetNames
          );
          return;
        }
        final var extraType = fieldByName(targetFields, extraTgt).type();
        if (!isSameType(primaryType, extraType)) {
          error(
            source,
            "@Bridge renames source=\"" +
              srcName +
              "\" fans out to targets with different types — \"" +
              primaryTgt +
              "\" is " +
              primaryType +
              ", \"" +
              extraTgt +
              "\" is " +
              extraType +
              ". Forward-only fan-out writes the same source value into every target; all targets must" +
              " share the same type."
          );
          return;
        }
      }
    }
    final var reverseRenames = new LinkedHashMap<String, String>();
    for (final var e : renames.entrySet()) reverseRenames.put(e.getValue(), e.getKey());
    // Fan-out extras: every extra target reads from the same source as the primary. Adding them to
    // reverseRenames lets readForward emit each extra target's read expression off the same source.
    for (final var fanout : renameFanouts.entrySet()) {
      for (final var extraTgt : fanout.getValue()) reverseRenames.put(extraTgt, fanout.getKey());
    }

    // Validate constants + computes (forward-only target-side injections). Each named field must
    // exist on the target; a target field may not be injected by more than one mechanism, nor by a
    // mechanism that also reaches that target name through a rename. Parse constant values here so
    // type errors fire alongside the rest of the per-pair validation.
    final var renameTargetNames = new LinkedHashSet<>(renames.values());
    for (final var extras : renameFanouts.values()) renameTargetNames.addAll(extras);

    // Validate @Default rows (source-side null-coalescing). Each named field must exist on the
    // source, must be a reference type (primitives can never be null), and must not overlap with
    // drops (the two have incompatible semantics: drop discards the value, default substitutes one
    // when null). The value parses against the source field's type using the same parser as
    // @Constant — String, primitives, "null" for reference types.
    final var parsedDefaults = new LinkedHashMap<String, String>();
    for (final var e : rawDefaults.entrySet()) {
      final var fieldName = e.getKey();
      final var sf = sourceFields
        .stream()
        .filter(f -> f.name().equals(fieldName))
        .findFirst()
        .orElse(null);
      if (sf == null) {
        error(
          source,
          "@Bridge defaults field=\"" +
            fieldName +
            "\" is not a field of " +
            source.getSimpleName() +
            " — known fields: " +
            sourceNames
        );
        return;
      }
      if (sf.type().getKind().isPrimitive()) {
        error(
          source,
          "@Bridge defaults field=\"" +
            fieldName +
            "\" has primitive type " +
            sf.type() +
            " — primitives cannot be null, so the default would never fire. Use the wrapper type or rework the source shape."
        );
        return;
      }
      if (drops.contains(fieldName)) {
        error(
          source,
          "@Bridge field \"" +
            fieldName +
            "\" appears in both defaults and drops — pick one (drop discards, default substitutes when null)."
        );
        return;
      }
      // FD-1: defaults + transforms on the same field stack two modifiers with undefined
      // ordering — the @Transform decides the conversion, the @Default wraps the source read in
      // a null-coalesce that the transform then consumes. The combination is structurally
      // accidental rather than designed; reject explicitly so the user picks one.
      if (transforms.containsKey(fieldName)) {
        error(
          source,
          "@Bridge field \"" +
            fieldName +
            "\" appears in both defaults and transforms — pick one (use a transform that handles null internally, or remove the default)."
        );
        return;
      }
      final var lit = parseConstantLiteral(source, fieldName, e.getValue(), sf.type());
      if (lit == null) return;
      parsedDefaults.put(fieldName, lit);
    }

    final var injectedTargetFields = new LinkedHashSet<String>();
    final var parsedConstants = new LinkedHashMap<String, String>();
    for (final var e : rawConstants.entrySet()) {
      final var fieldName = e.getKey();
      final var tf = targetFields
        .stream()
        .filter(f -> f.name().equals(fieldName))
        .findFirst()
        .orElse(null);
      if (tf == null) {
        error(
          source,
          "@Bridge constants field=\"" +
            fieldName +
            "\" is not a field of " +
            target.getSimpleName() +
            " — known fields: " +
            targetNames
        );
        return;
      }
      if (renameTargetNames.contains(fieldName)) {
        error(
          source,
          "@Bridge target \"" + fieldName + "\" appears in both renames (target slot) and constants — pick one."
        );
        return;
      }
      final var lit = parseConstantLiteral(source, fieldName, e.getValue(), tf.type());
      if (lit == null) return;
      parsedConstants.put(fieldName, lit);
      injectedTargetFields.add(fieldName);
    }
    for (final var e : computes.entrySet()) {
      final var fieldName = e.getKey();
      if (!targetNames.contains(fieldName)) {
        error(
          source,
          "@Bridge computes field=\"" +
            fieldName +
            "\" is not a field of " +
            target.getSimpleName() +
            " — known fields: " +
            targetNames
        );
        return;
      }
      if (parsedConstants.containsKey(fieldName)) {
        error(
          source,
          "@Bridge target \"" +
            fieldName +
            "\" appears in both constants and computes — pick one (constants inject a literal; computes inject a Supplier result)."
        );
        return;
      }
      if (renameTargetNames.contains(fieldName)) {
        error(
          source,
          "@Bridge target \"" + fieldName + "\" appears in both renames (target slot) and computes — pick one."
        );
        return;
      }
      injectedTargetFields.add(fieldName);
    }

    // Bijection check: apply forward renames on the source side, then compare to target names —
    // skipping target names covered by constants/computes (injected; no source counterpart needed).
    // Dropped sources are excluded from the check entirely.
    final var nonDroppedSourceFields = drops.isEmpty()
      ? sourceFields
      : sourceFields
          .stream()
          .filter(f -> !drops.contains(f.name()))
          .toList();
    if (
      !sameNames(source, nonDroppedSourceFields, target, targetFields, renames, renameFanouts, injectedTargetFields)
    ) return;

    // Build per-field "read expression" recipes: identity, sub-pair recursion, or container lift.
    // The reads need to know how to convert each source-field-value into the matching target-field-
    // value (and vice versa). Per-field decisions can also enqueue new TypePairs to emit. Plans are
    // keyed by SOURCE field name; the target side is looked up via the rename map.
    final var fieldPlans = planFields(
      source,
      target,
      nonDroppedSourceFields,
      targetFields,
      renames,
      transforms,
      viaMappers,
      pending,
      seen,
      userDeclared
    );
    if (fieldPlans == null) return;

    // readForward is called with TARGET field names (we walk targetFields). Injected targets
    // (constants, computes) take priority — they're forward-only literal/Supplier expressions with
    // no source counterpart. Otherwise reverse-rename to find the matching source field, then use
    // the source name to look up the plan and read the source. When the source field has a
    // @Default, wrap the source read in a (s.x() == null ? <literal> : s.x()) coalesce so the
    // forward direction substitutes the default for null sources.
    final Function<String, String> readForward = targetName -> {
      if (parsedConstants.containsKey(targetName)) return parsedConstants.get(targetName);
      if (computes.containsKey(targetName)) return "__cp_" + targetName + ".get()";
      final var srcName = reverseRenames.getOrDefault(targetName, targetName);
      final var rawRead = readExpr(source, "s", fieldByName(nonDroppedSourceFields, srcName));
      final var read = parsedDefaults.containsKey(srcName)
        ? "(" + rawRead + " == null ? " + parsedDefaults.get(srcName) + " : " + rawRead + ")"
        : rawRead;
      return applyForward(srcName, fieldPlans.get(srcName), read);
    };
    // readBackward is called with SOURCE field names. For drops AND forward-only transforms, emit
    // the type's zero value — both mechanisms have no defined backward and the source slot must be
    // filled with something. Otherwise forward-rename the source name to find the matching target
    // field for the read.
    final Function<String, String> readBackward = sourceName -> {
      if (drops.contains(sourceName)) return defaultLiteralFor(fieldByName(sourceFields, sourceName).type());
      if (forwardOnlyTransforms.contains(sourceName)) return defaultLiteralFor(
        fieldByName(sourceFields, sourceName).type()
      );
      final var tgtName = renames.getOrDefault(sourceName, sourceName);
      return applyBackward(
        sourceName,
        fieldPlans.get(sourceName),
        readExpr(target, "t", fieldByName(targetFields, tgtName))
      );
    };

    // Pass `source` as the annotation site so write-strategy errors land at the user's @Bridge
    // declaration rather than at `target` (which may be a third-party POJO with no annotation).
    final var forwardBody = buildExpr(target, readForward, targetFields, writeStrategy, source);
    if (forwardBody == null) return;
    final var backwardBody = buildExpr(source, readBackward, sourceFields, writeStrategy, source);
    if (backwardBody == null) return;

    final var imports = new TreeSet<>(importsFor(fieldPlans));
    imports.add("io.github.eschizoid.telescope.conversion.BridgeFn");
    writeClass(
      qualifiedBridge,
      bridgeName,
      imports,
      "Generated by telescope-codegen for @Bridge " +
        source.getSimpleName() +
        (userDeclared.contains(thisPair) ? "." : " (auto-generated for nested sub-pair " + targetFq + ")."),
      source,
      out -> {
        // Per-field @Transform: one static instance of each user-declared BridgeFn
        // implementation,
        // named `__tx_<sourceField>`. applyForward / applyBackward emit
        // `__tx_<field>.forward(...)`
        // / `.backward(...)` on the transformed slot.
        if (!transforms.isEmpty()) {
          for (final var e : transforms.entrySet()) {
            out.println(
              "  private static final " + e.getValue() + " __tx_" + e.getKey() + " = new " + e.getValue() + "();"
            );
          }
          out.println();
        }
        // Per-field @Compute: one static instance of each user-declared Supplier implementation,
        // named `__cp_<targetField>`. readForward emits `__cp_<field>.get()` for the forward
        // slot.
        if (!computes.isEmpty()) {
          for (final var e : computes.entrySet()) {
            out.println(
              "  private static final " + e.getValue() + " __cp_" + e.getKey() + " = new " + e.getValue() + "();"
            );
          }
          out.println();
        }
        // Static forward / backward methods so child bridges can reference us by
        // `BridgeName.forward(...)` / `.backward(...)` (direct static calls; no method-ref
        // lambda).
        // The Telescope BRIDGE wraps a nested concrete BridgeFn impl whose own forward/backward
        // delegate to these statics — one concrete dispatch type per @Bridge keeps the call site
        // monomorphic and lets the JIT inline the chain BRIDGE.read(s) -> Iso.to -> Fn.forward ->
        // <Bridge>.forward(s). The legacy Telescope.from(...).using(::forward, ::backward) path
        // shared one anonymous Iso class body across every bridge, going megamorphic on
        // Function::apply as more bridges were loaded.
        out.println("  public static " + targetFq + " forward(final " + sourceFq + " s) {");
        emitMethodBody(out, forwardBody);
        out.println("  }");
        out.println();
        out.println("  public static " + sourceFq + " backward(final " + targetFq + " t) {");
        emitMethodBody(out, backwardBody);
        out.println("  }");
        out.println();
        out.println("  /** One concrete BridgeFn type per @Bridge — monomorphic dispatch site. */");
        out.println("  private static final class Fn implements BridgeFn<" + sourceFq + ", " + targetFq + "> {");
        out.println("    @Override");
        out.println("    public " + targetFq + " forward(final " + sourceFq + " s) {");
        out.println("      return " + bridgeName + ".forward(s);");
        out.println("    }");
        out.println();
        out.println("    @Override");
        out.println("    public " + sourceFq + " backward(final " + targetFq + " t) {");
        out.println("      return " + bridgeName + ".backward(t);");
        out.println("    }");
        out.println("  }");
        out.println();
        out.println(
          "  public static final Telescope<" + sourceFq + ", " + targetFq + "> BRIDGE = Telescope.bridge(new Fn());"
        );
        emitContainerHelpers(out, fieldPlans, nonDroppedSourceFields, targetFields, renames);
      }
    );
  }

  // Sealed-source bridge: dispatch on the permits clause and delegate each case to the per-case
  // bridge that the user already declared with @Bridge on the subtype. The emitted forward/backward
  // are pattern-match switches over the sealed permits; no field walking, no rebuild — just one
  // dispatch arm per case. Requires every permit case to be @Bridge-annotated and its target to be
  // a permit of the sealed target.
  private void generateSealed(
    final TypeElement source,
    final TypeElement target,
    final Deque<TypePair> pending,
    final Set<TypePair> seen,
    final Set<TypePair> userDeclared
  ) {
    if (target.getKind() != ElementKind.INTERFACE || !target.getModifiers().contains(Modifier.SEALED)) {
      error(
        source,
        "@Bridge on a sealed interface requires the target to also be a sealed interface; " +
          target.getQualifiedName() +
          " is not."
      );
      return;
    }
    final var sourcePermits = source.getPermittedSubclasses();
    if (sourcePermits.isEmpty()) {
      error(source, "@Bridge on a sealed interface requires an explicit permits clause.");
      return;
    }
    final var targetPermitsFq = new HashSet<String>();
    for (final var tp : target.getPermittedSubclasses()) {
      if (tp.getKind() == TypeKind.DECLARED) {
        targetPermitsFq.add(((TypeElement) ((DeclaredType) tp).asElement()).getQualifiedName().toString());
      }
    }

    record CaseEntry(TypeElement sourceCase, TypeElement targetCase, String bridgeFq) {}
    final List<CaseEntry> entries = new ArrayList<>();
    for (final var sp : sourcePermits) {
      if (sp.getKind() != TypeKind.DECLARED) continue;
      final var sourceCaseEl = (TypeElement) ((DeclaredType) sp).asElement();
      // Find the @Bridge on this permit case whose target lives in the sealed-target's permits.
      // A case may have multiple @Bridge annotations (one for the sealed-target side, others for
      // unrelated targets); only the one matching the sealed target wires the dispatch switch.
      final var caseBridges = collectBridgeAnnotations(sourceCaseEl);
      TypeMirror caseTarget = null;
      for (final var bridgeAm : caseBridges) {
        final var tm = targetTypeFromMirror(bridgeAm);
        if (tm == null || tm.getKind() != TypeKind.DECLARED) continue;
        final var tEl = (TypeElement) ((DeclaredType) tm).asElement();
        if (targetPermitsFq.contains(tEl.getQualifiedName().toString())) {
          caseTarget = tm;
          break;
        }
      }
      if (caseTarget == null) {
        if (caseBridges.isEmpty()) {
          error(
            source,
            "Subtype " +
              sourceCaseEl.getSimpleName() +
              " of @Bridge sealed " +
              source.getSimpleName() +
              " must itself be @Bridge-annotated."
          );
        } else if (caseBridges.size() == 1) {
          // Single @Bridge whose target isn't in the sealed-target permits — name it explicitly.
          final var only = targetTypeFromMirror(caseBridges.get(0));
          final var onlyEl = (TypeElement) ((DeclaredType) only).asElement();
          error(
            source,
            "Subtype " +
              sourceCaseEl.getSimpleName() +
              "'s @Bridge target " +
              onlyEl.getQualifiedName() +
              " is not a permits of sealed target " +
              target.getQualifiedName() +
              "."
          );
        } else {
          // Multi-target — none of the @Bridge targets matched the sealed-target permits.
          error(
            source,
            "Subtype " +
              sourceCaseEl.getSimpleName() +
              " has multiple @Bridge targets, none of which is a permits of sealed target " +
              target.getQualifiedName() +
              "."
          );
        }
        return;
      }
      final var targetCaseEl = (TypeElement) ((DeclaredType) caseTarget).asElement();
      final var targetCaseFq = targetCaseEl.getQualifiedName().toString();
      final var caseSourceFq = sourceCaseEl.getQualifiedName().toString();
      final var caseUseShortName = !multiTargetSources.contains(caseSourceFq);
      final var caseBridgeSimple = bridgeClassName(sourceCaseEl, targetCaseEl, caseUseShortName);
      final var caseSourcePkg = processingEnv
        .getElementUtils()
        .getPackageOf(sourceCaseEl)
        .getQualifiedName()
        .toString();
      final var caseBridgeFq = caseSourcePkg.isEmpty() ? caseBridgeSimple : caseSourcePkg + "." + caseBridgeSimple;
      entries.add(new CaseEntry(sourceCaseEl, targetCaseEl, caseBridgeFq));
      // Defensively enqueue the per-case pair too — if the user @Bridge'd it (required), it's
      // already in the queue; this is idempotent via `seen`.
      final var casePair = new TypePair(sourceCaseEl.getQualifiedName().toString(), targetCaseFq);
      if (seen.add(casePair)) pending.add(casePair);
    }

    final var pkg = processingEnv.getElementUtils().getPackageOf(source).getQualifiedName().toString();
    final var sourceFq = source.getQualifiedName().toString();
    final var targetFq = target.getQualifiedName().toString();
    final var thisPair = new TypePair(sourceFq, targetFq);
    final var useShortName = userDeclared.contains(thisPair) && !multiTargetSources.contains(sourceFq);
    final var bridgeName = bridgeClassName(source, target, useShortName);
    final var qualifiedBridge = pkg.isEmpty() ? bridgeName : pkg + "." + bridgeName;

    writeClass(
      qualifiedBridge,
      bridgeName,
      Set.of(
        "io.github.eschizoid.telescope.conversion.BridgeFn",
        "io.github.eschizoid.telescope.conversion.Match",
        "java.util.function.Function"
      ),
      "Generated by telescope-codegen for @Bridge sealed " + source.getSimpleName() + ".",
      source,
      out -> {
        // Sealed-dispatch via Match — the dispatch is lattice-routed (Match composes internal
        // Prism instances per permit) and exhaustiveness is verified at class-load time by
        // .exhaustive() reading getPermittedSubclasses(). Belt-and-suspenders: if the processor
        // misses a permit, .exhaustive() throws loudly at class init rather than silently
        // defaulting to the unreachable-throw at first invocation of the orphan case.
        out.println("  private static final Function<" + sourceFq + ", " + targetFq + "> FORWARD =");
        out.println("    Match.<" + sourceFq + ", " + targetFq + ">of(" + sourceFq + ".class)");
        for (final var e : entries) {
          out.println("      .when(" + e.sourceCase().getQualifiedName() + ".class, " + e.bridgeFq() + "::forward)");
        }
        out.println("      .exhaustive();");
        out.println();
        out.println("  private static final Function<" + targetFq + ", " + sourceFq + "> BACKWARD =");
        out.println("    Match.<" + targetFq + ", " + sourceFq + ">of(" + targetFq + ".class)");
        for (final var e : entries) {
          out.println("      .when(" + e.targetCase().getQualifiedName() + ".class, " + e.bridgeFq() + "::backward)");
        }
        out.println("      .exhaustive();");
        out.println();
        out.println("  public static " + targetFq + " forward(final " + sourceFq + " s) {");
        out.println("    return FORWARD.apply(s);");
        out.println("  }");
        out.println();
        out.println("  public static " + sourceFq + " backward(final " + targetFq + " t) {");
        out.println("    return BACKWARD.apply(t);");
        out.println("  }");
        out.println();
        out.println("  /** One concrete BridgeFn type per @Bridge — monomorphic dispatch site. */");
        out.println("  private static final class Fn implements BridgeFn<" + sourceFq + ", " + targetFq + "> {");
        out.println("    @Override");
        out.println("    public " + targetFq + " forward(final " + sourceFq + " s) {");
        out.println("      return " + bridgeName + ".forward(s);");
        out.println("    }");
        out.println();
        out.println("    @Override");
        out.println("    public " + sourceFq + " backward(final " + targetFq + " t) {");
        out.println("      return " + bridgeName + ".backward(t);");
        out.println("    }");
        out.println("  }");
        out.println();
        out.println(
          "  public static final Telescope<" + sourceFq + ", " + targetFq + "> BRIDGE = Telescope.bridge(new Fn());"
        );
      }
    );
  }

  private static String camelLower(final String simpleName) {
    if (simpleName.isEmpty()) return simpleName;
    return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
  }

  /**
   * Emit the method body for {@code body} returned from {@link #buildExpr}. {@code body} comes in
   * two shapes: an <em>expression</em> ({@code new Foo(...)}, {@code Foo.builder()...build()}) that
   * needs wrapping with {@code return ... ;}, or a <em>block</em> ({@code { final var out = ...;
   * ... return out; }}) that's already a statement list and just needs the braces stripped.
   */
  private static void emitMethodBody(final PrintWriter out, final String body) {
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
      TRANSFORM,
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
        case "java.util.List" -> args.size() == 1 ? new ContainerShape(FieldPlan.Kind.LIST, args.get(0), null) : null;
        case "java.util.Set" -> args.size() == 1 ? new ContainerShape(FieldPlan.Kind.SET, args.get(0), null) : null;
        case "java.util.Optional" -> args.size() == 1
          ? new ContainerShape(FieldPlan.Kind.OPTIONAL, args.get(0), null)
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
  private Map<String, FieldPlan> planFields(
    final TypeElement source,
    final TypeElement target,
    final List<Field> sourceFields,
    final List<Field> targetFields,
    final Map<String, String> renames,
    final Map<String, String> transforms,
    final Map<String, String> viaMappers,
    final Deque<TypePair> pending,
    final Set<TypePair> seen,
    final Set<TypePair> userDeclared
  ) {
    final var plans = new LinkedHashMap<String, FieldPlan>();
    for (final var sf : sourceFields) {
      // Per-field transform supersedes the type-match logic — the transform IS the contract.
      if (transforms.containsKey(sf.name())) {
        plans.put(sf.name(), FieldPlan.ofKind(FieldPlan.Kind.TRANSFORM, transforms.get(sf.name())));
        continue;
      }
      // Per-field viaMapper supersedes auto-recursion — the user-supplied bridge class IS the
      // contract. Emit a RECURSE plan whose subBridgeName is the user's class FQN; applyForward /
      // applyBackward already emit `<class>.forward(...)` / `<class>.backward(...)` for RECURSE,
      // so no new dispatch arm is needed.
      if (viaMappers.containsKey(sf.name())) {
        plans.put(sf.name(), FieldPlan.recurse(viaMappers.get(sf.name())));
        continue;
      }
      final var tf = fieldByName(targetFields, renames.getOrDefault(sf.name(), sf.name()));
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

  private String applyForward(final String fieldName, final FieldPlan plan, final String readExpr) {
    final var sub = plan.subBridgeName();
    final boolean elementIdentity = IDENTITY_ELEMENT_SENTINEL.equals(sub);
    final var fwdElement = elementIdentity ? "e -> e" : sub + "::forward";
    return switch (plan.kind()) {
      case IDENTITY -> readExpr;
      case RECURSE -> sub + ".forward(" + readExpr + ")";
      // LIST/SET/MAP_VALUES: when the element type needs a sub-bridge, delegate to a private static
      // helper emitted alongside this method (see emitContainerHelpers below). The helper inlines a
      // size-presized for-loop, eliminating the Stream + Spliterator + collector overhead at the
      // dispatch site. When the element type is identity (same on both sides), a defensive copy is
      // sufficient and we emit it inline.
      case LIST -> elementIdentity
        ? "(" + readExpr + " == null ? null : new ArrayList<>(" + readExpr + "))"
        : "__fwd_" + fieldName + "(" + readExpr + ")";
      case SET -> elementIdentity
        ? "(" + readExpr + " == null ? null : new LinkedHashSet<>(" + readExpr + "))"
        : "__fwd_" + fieldName + "(" + readExpr + ")";
      case OPTIONAL -> readExpr + ".map(" + fwdElement + ")";
      case MAP_VALUES -> elementIdentity
        ? "(" + readExpr + " == null ? null : new LinkedHashMap<>(" + readExpr + "))"
        : "__fwd_" + fieldName + "(" + readExpr + ")";
      case OPTIONAL_TO_NULLABLE -> readExpr + ".map(" + fwdElement + ").orElse(null)";
      case NULLABLE_TO_OPTIONAL -> "Optional.ofNullable(" + readExpr + ").map(" + fwdElement + ")";
      case TRANSFORM -> "__tx_" + fieldName + ".forward(" + readExpr + ")";
    };
  }

  private String applyBackward(final String fieldName, final FieldPlan plan, final String readExpr) {
    final var sub = plan.subBridgeName();
    final boolean elementIdentity = IDENTITY_ELEMENT_SENTINEL.equals(sub);
    final var bwdElement = elementIdentity ? "e -> e" : sub + "::backward";
    return switch (plan.kind()) {
      case IDENTITY -> readExpr;
      case RECURSE -> sub + ".backward(" + readExpr + ")";
      case LIST -> elementIdentity
        ? "(" + readExpr + " == null ? null : new ArrayList<>(" + readExpr + "))"
        : "__bwd_" + fieldName + "(" + readExpr + ")";
      case SET -> elementIdentity
        ? "(" + readExpr + " == null ? null : new LinkedHashSet<>(" + readExpr + "))"
        : "__bwd_" + fieldName + "(" + readExpr + ")";
      case OPTIONAL -> readExpr + ".map(" + bwdElement + ")";
      case MAP_VALUES -> elementIdentity
        ? "(" + readExpr + " == null ? null : new LinkedHashMap<>(" + readExpr + "))"
        : "__bwd_" + fieldName + "(" + readExpr + ")";
      // For the cross-paradigm bridges, forward and backward are mirror images.
      case OPTIONAL_TO_NULLABLE -> "Optional.ofNullable(" + readExpr + ").map(" + bwdElement + ")";
      case NULLABLE_TO_OPTIONAL -> readExpr + ".map(" + bwdElement + ").orElse(null)";
      case TRANSFORM -> "__tx_" + fieldName + ".backward(" + readExpr + ")";
    };
  }

  /**
   * Compute the {@code java.util.*} imports a bridge needs based on the kinds of fields in its
   * plan. Returned set is fed into {@link AbstractTelescopeProcessor#writeClass(String, String,
   * Set, String, Element, java.util.function.Consumer)} so the emitted file has clean imports
   * instead of FQNs in the body.
   */
  private Set<String> importsFor(final Map<String, FieldPlan> fieldPlans) {
    final var imports = new TreeSet<String>();
    for (final var plan : fieldPlans.values()) {
      switch (plan.kind()) {
        case LIST -> {
          imports.add("java.util.List");
          imports.add("java.util.ArrayList");
        }
        case SET -> {
          imports.add("java.util.Set");
          imports.add("java.util.LinkedHashSet");
        }
        case MAP_VALUES -> {
          imports.add("java.util.Map");
          imports.add("java.util.LinkedHashMap");
        }
        case OPTIONAL_TO_NULLABLE, NULLABLE_TO_OPTIONAL -> imports.add("java.util.Optional");
        default -> {
        }
      }
    }
    return imports;
  }

  /**
   * Emit one private static helper per LIST/SET/MAP_VALUES field whose element type carries a
   * sub-bridge. Each helper inlines a size-presized for-loop with a direct static call to the
   * sub-bridge's forward/backward — no {@code Stream} pipeline allocation, no {@code
   * Function::apply} hop. Mirrors what MapStruct emits for the same shape; closes the deep-tier
   * dispatch gap.
   */
  private void emitContainerHelpers(
    final PrintWriter out,
    final Map<String, FieldPlan> fieldPlans,
    final List<Field> sourceFields,
    final List<Field> targetFields,
    final Map<String, String> renames
  ) {
    for (final var entry : fieldPlans.entrySet()) {
      final var fieldName = entry.getKey();
      final var plan = entry.getValue();
      if (IDENTITY_ELEMENT_SENTINEL.equals(plan.subBridgeName())) continue;
      final var srcType = fieldByName(sourceFields, fieldName).type();
      final var tgtType = fieldByName(targetFields, renames.getOrDefault(fieldName, fieldName)).type();
      switch (plan.kind()) {
        case LIST -> {
          emitListHelper(out, "__fwd_" + fieldName, srcType, tgtType, plan.subBridgeName(), "forward");
          emitListHelper(out, "__bwd_" + fieldName, tgtType, srcType, plan.subBridgeName(), "backward");
        }
        case SET -> {
          emitSetHelper(out, "__fwd_" + fieldName, srcType, tgtType, plan.subBridgeName(), "forward");
          emitSetHelper(out, "__bwd_" + fieldName, tgtType, srcType, plan.subBridgeName(), "backward");
        }
        case MAP_VALUES -> {
          emitMapHelper(out, "__fwd_" + fieldName, srcType, tgtType, plan.subBridgeName(), "forward");
          emitMapHelper(out, "__bwd_" + fieldName, tgtType, srcType, plan.subBridgeName(), "backward");
        }
        default -> {
        }
      }
    }
  }

  private void emitListHelper(
    final PrintWriter out,
    final String name,
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final String subBridge,
    final String direction
  ) {
    final var srcElement = ((DeclaredType) srcContainer).getTypeArguments().get(0);
    final var tgtElement = ((DeclaredType) tgtContainer).getTypeArguments().get(0);
    out.println();
    out.println("  private static List<" + tgtElement + "> " + name + "(final List<" + srcElement + "> src) {");
    out.println("    if (src == null) return null;");
    out.println("    final var out = new ArrayList<" + tgtElement + ">(src.size());");
    out.println("    for (final var x : src) out.add(" + subBridge + "." + direction + "(x));");
    out.println("    return out;");
    out.println("  }");
  }

  private void emitSetHelper(
    final PrintWriter out,
    final String name,
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final String subBridge,
    final String direction
  ) {
    final var srcElement = ((DeclaredType) srcContainer).getTypeArguments().get(0);
    final var tgtElement = ((DeclaredType) tgtContainer).getTypeArguments().get(0);
    out.println();
    out.println("  private static Set<" + tgtElement + "> " + name + "(final Set<" + srcElement + "> src) {");
    out.println("    if (src == null) return null;");
    out.println("    final var out = new LinkedHashSet<" + tgtElement + ">(src.size());");
    out.println("    for (final var x : src) out.add(" + subBridge + "." + direction + "(x));");
    out.println("    return out;");
    out.println("  }");
  }

  private void emitMapHelper(
    final PrintWriter out,
    final String name,
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final String subBridge,
    final String direction
  ) {
    final var srcArgs = ((DeclaredType) srcContainer).getTypeArguments();
    final var tgtArgs = ((DeclaredType) tgtContainer).getTypeArguments();
    final var keyType = srcArgs.get(0);
    final var srcValue = srcArgs.get(1);
    final var tgtValue = tgtArgs.get(1);
    out.println();
    out.println(
      "  private static Map<" +
        keyType +
        ", " +
        tgtValue +
        "> " +
        name +
        "(final Map<" +
        keyType +
        ", " +
        srcValue +
        "> src) {"
    );
    out.println("    if (src == null) return null;");
    out.println("    final var out = new LinkedHashMap<" + keyType + ", " + tgtValue + ">(src.size());");
    out.println(
      "    for (final var e : src.entrySet()) out.put(e.getKey(), " + subBridge + "." + direction + "(e.getValue()));"
    );
    out.println("    return out;");
    out.println("  }");
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
  // failure (error reported on `annotationSite` so the diagnostic lands at the user's @Bridge,
  // not at the target class, which may be third-party).
  //
  // writeStrategy: AUTO (run the priority ladder), CONSTRUCTOR / BUILDER / SETTERS (force one).
  // Records always use the canonical constructor regardless of the strategy.
  private String buildExpr(
    final TypeElement to,
    final Function<String, String> read,
    final List<Field> toFields,
    final String writeStrategy,
    final TypeElement annotationSite
  ) {
    final var toFq = to.getQualifiedName().toString();
    if (to.getKind() == ElementKind.RECORD) {
      final var args = to
        .getRecordComponents()
        .stream()
        .map(c -> read.apply(c.getSimpleName().toString()))
        .collect(Collectors.joining(", "));
      return "new " + toFq + "(" + args + ")";
    }

    final var auto = "AUTO".equals(writeStrategy);

    // POJO: a public constructor whose parameter names match the fields (order-independent).
    if (auto || "CONSTRUCTOR".equals(writeStrategy)) {
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
      if (!auto) {
        error(
          annotationSite,
          "@Bridge writeStrategy = CONSTRUCTOR on " +
            annotationSite.getQualifiedName() +
            " (target " +
            toFq +
            "): no public constructor whose parameter names match the bridge fields. Switch to AUTO, BUILDER, or SETTERS, or add a name-matched constructor."
        );
        return null;
      }
    }

    // POJO: a static builder() with a method per field.
    if (auto || "BUILDER".equals(writeStrategy)) {
      final var builder = staticBuilderMethod(to);
      if (builder != null && builder.getReturnType().getKind() == TypeKind.DECLARED) {
        final var builderType = (TypeElement) ((DeclaredType) builder.getReturnType()).asElement();
        final var sb = new StringBuilder(toFq + ".builder()");
        for (final var f : toFields) {
          final var method = builderSetter(builderType, f.name());
          if (method == null) {
            error(
              annotationSite,
              "@Bridge: builder " +
                builderType.getQualifiedName() +
                " (target " +
                toFq +
                ") has no method for '" +
                f.name() +
                "'"
            );
            return null;
          }
          sb.append(".").append(method).append("(").append(read.apply(f.name())).append(")");
        }
        return sb.append(".build()").toString();
      }
      if (!auto) {
        error(
          annotationSite,
          "@Bridge writeStrategy = BUILDER on " +
            annotationSite.getQualifiedName() +
            " (target " +
            toFq +
            "): no static builder() method returning a builder class. Switch to AUTO, CONSTRUCTOR, or SETTERS, or add a builder()."
        );
        return null;
      }
    }

    // POJO: a no-arg constructor plus a setter per field.
    if (auto || "SETTERS".equals(writeStrategy)) {
      if (hasPublicNoArgConstructor(to)) {
        final var sb = new StringBuilder("{ final var out = new " + toFq + "(); ");
        for (final var f : toFields) {
          final var setter = setterName(to, f.name());
          if (setter == null) {
            error(
              annotationSite,
              "@Bridge: " + toFq + " has a no-arg constructor but no setter for '" + f.name() + "'"
            );
            return null;
          }
          sb.append("out.").append(setter).append("(").append(read.apply(f.name())).append("); ");
        }
        return sb.append("return out; }").toString();
      }
      if (!auto) {
        error(
          annotationSite,
          "@Bridge writeStrategy = SETTERS on " +
            annotationSite.getQualifiedName() +
            " (target " +
            toFq +
            "): no public no-arg constructor. Switch to AUTO, CONSTRUCTOR, or BUILDER, or add a no-arg constructor."
        );
        return null;
      }
    }

    error(
      annotationSite,
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
    final List<Field> targetFields,
    final Map<String, String> renames,
    final Map<String, List<String>> renameFanouts,
    final Set<String> injectedTargetFields
  ) {
    final var sn = new TreeSet<String>();
    for (final var f : sourceFields) {
      sn.add(renames.getOrDefault(f.name(), f.name()));
      // Forward-only fan-out: every extra target counts as a source-derived name in the bijection,
      // since forward writes the source value into every fan-out target slot.
      final var extras = renameFanouts.get(f.name());
      if (extras != null) sn.addAll(extras);
    }
    final var tn = targetFields
      .stream()
      .map(Field::name)
      .filter(n -> !injectedTargetFields.contains(n))
      .collect(Collectors.toCollection(TreeSet::new));
    if (sn.equals(tn)) return true;
    error(
      source,
      "@Bridge: " +
        source.getSimpleName() +
        " and " +
        target.getSimpleName() +
        " must expose the same field names (a bijection). " +
        source.getSimpleName() +
        (renames.isEmpty() ? " has " : " (after renames) has ") +
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
}
