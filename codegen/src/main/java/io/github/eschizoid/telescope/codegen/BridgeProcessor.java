package io.github.eschizoid.telescope.codegen;

import java.io.IOException;
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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

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
 *
 * <p><b>Round-deferred emission for Lombok-annotated targets.</b> Lombok installs lazy AST visitors
 * during processor init that patch class declarations on traversal. Those visitors may not have
 * fired by round 1, so a processor that queries {@link
 * javax.lang.model.util.Elements#getAllMembers} for a {@code @Data} class in round 1 may see the
 * un-patched member list (no getters / setters / builder). When this processor detects that the
 * source or target of a {@code @Bridge} pair carries any Lombok-synthesizing annotation (see {@link
 * AbstractTelescopeProcessor#LOMBOK_SYNTHESIZING_ANNOTATIONS}), the pair is held back and emitted
 * only when {@link RoundEnvironment#processingOver} is true — by then Lombok is guaranteed done
 * patching. Pure record/POJO bridges keep the round-1 fast path with zero behavioural change.
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

  /**
   * Single per-pair configuration record holding every modifier parsed from the {@code @Bridge}
   * annotation. Consolidating ten separate {@code Map<TypePair, ?>} fields into one {@code
   * Map<TypePair, BridgeConfig>} eliminates the {@code clear()} maintenance hazard — adding a new
   * attribute means adding a record component, not declaring a new instance field AND remembering
   * to clear it at the top of each {@link #process(Set, RoundEnvironment)} round.
   *
   * <p>Each component holds the per-pair view of one modifier; an empty map / set / null
   * placeholder is fine when that modifier wasn't used on the pair. {@link #EMPTY} is the
   * fall-through value for pairs that were never registered.
   */
  private record BridgeConfig(
    Set<String> drops,
    Map<String, String> renames,
    Map<String, List<String>> renameFanouts,
    Map<String, String> transforms,
    Set<String> forwardOnlyTransforms,
    Map<String, String> defaults,
    Map<String, String> viaMappers,
    String writeStrategy,
    Map<String, String> constants,
    Map<String, String> computes,
    // Per-source-field qualifier method name. Set when @Transform supplies a non-empty `method`
    // attribute — codegen emits a direct {@code UsingClass.methodName(value)} call instead of
    // instantiating a BridgeFn. Always implicitly forward-only; the source field also lands in
    // forwardOnlyTransforms when this map carries an entry for it.
    Map<String, String> transformMethods,
    // Carrier-form emission override (ADR-0007 / Enh 1). When set, the generated bridge class
    // lives in this carrier's package and is named after the carrier — the source's package and
    // simple name are ignored for emission purposes. `null` means model-anchored form (the legacy
    // path: source class IS the annotated element, package + name derive from it).
    String carrierFq,
    // @Bridge(lenient = true) — when set, the bijection check is skipped: unmatched target
    // components take their JLS default in the forward direction; unmatched source components are
    // silently ignored. Produces a partial-Iso whose backward (BRIDGE.set(source, target))
    // direction is documented as lossy.
    boolean lenient
  ) {
    static final BridgeConfig EMPTY = new BridgeConfig(
      Set.of(),
      Map.of(),
      Map.of(),
      Map.of(),
      Set.of(),
      Map.of(),
      Map.of(),
      "AUTO",
      Map.of(),
      Map.of(),
      Map.of(),
      null,
      false
    );
  }

  // One map indexed by TypePair carries every modifier parsed from @Bridge. Replaces ten separate
  // *ByPair fields whose individual clear() calls were a maintenance hazard (forgetting one on a
  // new attribute = cross-round stale state).
  private final Map<TypePair, BridgeConfig> configsByPair = new HashMap<>();

  // Pairs whose source or target carries a Lombok-synthesizing annotation. Their emission waits
  // until processingOver() so Lombok's lazy AST patches have all fired. Top-level deferred pairs
  // carry their full BridgeConfig in deferredConfigs; sub-pairs discovered recursively during the
  // eager drain are added to deferredPairs WITHOUT a deferredConfigs entry (sub-pairs fall back to
  // BridgeConfig.EMPTY at lookup time, same as the auto-recursed case in the eager path).
  private final Set<TypePair> deferredPairs = new LinkedHashSet<>();
  private final Map<TypePair, BridgeConfig> deferredConfigs = new HashMap<>();

  // Sub-pairs that an enclosing lenient @Bridge referenced. A sub-pair carries no BridgeConfig of
  // its own (it falls back to BridgeConfig.EMPTY, which is strict), so without this its bijection
  // check would fail when the nested target has extra fields — even though the lenient parent
  // intends those to default. generate() ORs this into the per-pair lenient flag so leniency
  // propagates to every nested field/container level, matching the runtime mapperForward(...)
  // behaviour. Cleared in processingOver() so a reused processor instance starts clean.
  //
  // Sticky-wins resolution: a sub-bridge class is emitted once per type pair, so if the SAME pair
  // is
  // reached from both a lenient and a strict parent the single emitted sub-bridge is lenient for
  // both. That is more permissive, never less, so it cannot lose data the strict parent would have
  // kept; the trade is that a strict parent's nested mismatch no longer fails when a lenient
  // sibling
  // also references the pair. Splitting into per-parent sub-bridges would be the stricter (and much
  // larger) alternative.
  private final Set<TypePair> lenientPairs = new HashSet<>();

  // Set true around the deferred drain in processingOver() so sub-pair discovery inside
  // generate(...) / generateSealed(...) / planFieldSubBridges(...) / planElementSubBridge(...)
  // doesn't re-defer (we're ALREADY in the final pass — Lombok is patched).
  private boolean inDeferredDrain = false;

  // FQNs of generated <Carrier>BridgeProvider classes. A carrier-form bridge lives in the carrier's
  // package, which the source-keyed runtime probe can't reach — so each is registered as a
  // ServiceLoader provider (written to META-INF/services in processingOver()) for mapperForward's
  // package-agnostic registry. Cleared after the write so a reused instance starts clean.
  private final Set<String> bridgeProviders = new LinkedHashSet<>();

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var anno = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
    if (anno == null) return false;
    final var bridgesAnno = processingEnv.getElementUtils().getTypeElement(BRIDGES_ANNOTATION);
    // multiTargetSources and configsByPair accumulate across rounds. Clearing them at the top of
    // every round would wipe state populated in round 1 (when @Bridge elements are visible) before
    // the processingOver() deferred drain can read it, causing deferred sub-pairs to emit with the
    // wrong class name (short form for a source whose multi-target nature was just forgotten).
    // Cross-compilation leak isn't a risk — JSR-269 creates fresh processor instances per
    // compilation. State is reset at processingOver() after the final drain instead.

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
      final var bridges = collectBridgeAnnotations(element);
      if (bridges.isEmpty()) continue;

      // Carrier-form detection runs once per element, BEFORE the per-Bridge loop, because the
      // multi-target tracking (multiTargetSources) keys on the SOURCE FQN — and the source of a
      // carrier-anchored Bridge is the `source = ...` attribute, not the element itself. The
      // model-anchored form (the legacy path) treats the element as the source.
      if (bridges.size() > 1) {
        multiTargetSources.add(((TypeElement) element).getQualifiedName().toString());
      }

      for (final var bridgeAm : bridges) {
        // Carrier-form: `@Bridge(source = X.class, target = Y.class)` on a third class. The
        // annotated element is the CARRIER (just a package anchor + name); X is the actual source
        // and Y the actual target. Detect carrier form by checking whether `source = ...` is set
        // to anything other than its `Void.class` default sentinel. ADR-0007.
        final var rawSource = rawSourceValueFromMirror(bridgeAm);
        final var rawCarrierTarget = rawCarrierTargetFromMirror(bridgeAm);
        final var carrierForm = rawSource != null && !isVoidSentinel(rawSource);

        final TypeElement carrierEl = carrierForm ? (TypeElement) element : null;
        final TypeMirror sourceMirror;
        final TypeMirror targetMirror;
        if (carrierForm) {
          if (rawSource instanceof String fqn) {
            error(
              element,
              "@Bridge source '" +
                fqn +
                "' is not resolvable from this compilation unit — add the dependency, " +
                "or move the carrier class to a module that sees the source type."
            );
            continue;
          }
          sourceMirror = rawSource instanceof TypeMirror sm ? sm : null;
          if (sourceMirror == null || sourceMirror.getKind() != TypeKind.DECLARED) {
            error(element, "@Bridge source must be a class, record, or sealed-interface type");
            continue;
          }
          if (rawCarrierTarget == null || isVoidSentinel(rawCarrierTarget)) {
            error(
              element,
              "@Bridge: source = ... requires target = ... on the same annotation (carrier form needs both sides)."
            );
            continue;
          }
          if (rawCarrierTarget instanceof String tfqn) {
            error(
              element,
              "@Bridge target '" +
                tfqn +
                "' is not resolvable from this compilation unit — add the dependency, " +
                "or move the carrier class to a module that sees the target type."
            );
            continue;
          }
          targetMirror = rawCarrierTarget instanceof TypeMirror ttm ? ttm : null;
          if (targetMirror == null || targetMirror.getKind() != TypeKind.DECLARED) {
            error(element, "@Bridge target must be a class, record, or sealed-interface type");
            continue;
          }
        } else {
          // Model-anchored form — the legacy path. The annotated element IS the source; the target
          // comes from `value = ...`. If a user set neither `value` NOR `source/target`, surface a
          // precise diagnostic instead of silently accepting an empty annotation.
          final var rawValue = rawTargetValueFromMirror(bridgeAm);
          if (rawValue == null || isVoidSentinel(rawValue)) {
            error(
              element,
              "@Bridge requires either value = TargetClass.class (model-anchored form) or " +
                "source = ... + target = ... (carrier form)."
            );
            continue;
          }
          if (rawValue instanceof String fqn) {
            error(
              element,
              "@Bridge target '" +
                fqn +
                "' is not resolvable from this compilation unit — annotate the other side, " +
                "or add the dependency."
            );
            continue;
          }
          targetMirror = rawValue instanceof TypeMirror tm ? tm : null;
          if (targetMirror == null || targetMirror.getKind() != TypeKind.DECLARED) {
            error(element, "@Bridge value must be a class, record, or sealed-interface type");
            continue;
          }
          sourceMirror = ((TypeElement) element).asType();
        }
        final var sourceFq = carrierForm
          ? ((TypeElement) ((DeclaredType) sourceMirror).asElement()).getQualifiedName().toString()
          : ((TypeElement) element).getQualifiedName().toString();
        final var targetEl = (TypeElement) ((DeclaredType) targetMirror).asElement();
        if (targetEl.getNestingKind() != NestingKind.TOP_LEVEL) {
          error(element, "@Bridge target must be a top-level type");
          continue;
        }
        final TypeElement sourceEl = carrierForm
          ? (TypeElement) ((DeclaredType) sourceMirror).asElement()
          : (TypeElement) element;
        if (sourceEl.getNestingKind() != NestingKind.TOP_LEVEL) {
          error(element, "@Bridge source must be a top-level type");
          continue;
        }
        final var pair = new TypePair(sourceFq, targetEl.getQualifiedName().toString());
        userDeclared.add(pair);
        final var drops = dropsFromMirror(bridgeAm);
        final var renameSet = renamesFromMirror(element, bridgeAm);
        if (renameSet == null) continue; // invalid rename — error already reported, skip this pair
        final var transformSet = transformsFromMirror(element, bridgeAm);
        if (transformSet == null) continue; // invalid transform — already reported, skip this pair
        final var constants = constantsFromMirror(element, bridgeAm);
        if (constants == null) continue; // invalid constant — already reported, skip this pair
        final var computes = computesFromMirror(element, bridgeAm);
        if (computes == null) continue; // invalid compute — already reported, skip this pair
        final var rawDefaults = defaultsFromMirror(element, bridgeAm);
        if (rawDefaults == null) continue; // invalid default — already reported, skip this pair
        final var viaMappers = viaMappersFromMirror(element, bridgeAm);
        if (viaMappers == null) continue; // invalid viaMapper — already reported, skip this pair
        final var rawWriteStrategy = writeStrategyFromMirror(bridgeAm);
        final var writeStrategy = (rawWriteStrategy == null || "AUTO".equals(rawWriteStrategy))
          ? "AUTO"
          : rawWriteStrategy;
        final var lenient = lenientFromMirror(bridgeAm);
        final var builtConfig = new BridgeConfig(
          drops,
          renameSet.bySource(),
          renameSet.fanoutExtras(),
          transformSet.byField(),
          transformSet.forwardOnlyFields(),
          rawDefaults,
          viaMappers,
          writeStrategy,
          constants,
          computes,
          transformSet.methodsByField(),
          carrierEl != null ? carrierEl.getQualifiedName().toString() : null,
          lenient
        );
        // Defer when source OR target carries a Lombok-synthesizing annotation: Lombok's lazy AST
        // patches may not have fired yet, so member lookups via Elements.getAllMembers would return
        // the un-patched view. processingOver() guarantees Lombok is done patching. For carrier
        // form, the actual source/target classes (NOT the carrier) are what carry the Lombok
        // annotations, so check those.
        final var requiresDeferral =
          !roundEnv.processingOver() && (carriesLombokTrigger(sourceEl) || carriesLombokTrigger(targetEl));
        if (requiresDeferral) {
          deferredPairs.add(pair);
          deferredConfigs.put(pair, builtConfig);
        } else {
          configsByPair.put(pair, builtConfig);
          if (seen.add(pair)) pending.add(pair);
        }
      }
    }
    // Drain the eager queue, generating bridges for pure record/POJO pairs. Each generate(...) call
    // may discover sub-pairs and add them to `pending` for recursive emission. The `seen` set
    // guards against re-emission (cycle safety + multiple parent bridges sharing the same
    // sub-pair).
    while (!pending.isEmpty()) {
      final var pair = pending.poll();
      final var sourceEl = processingEnv.getElementUtils().getTypeElement(pair.sourceFq());
      final var targetEl = processingEnv.getElementUtils().getTypeElement(pair.targetFq());
      if (sourceEl == null || targetEl == null) continue;
      generate(sourceEl, targetEl, pending, seen, userDeclared);
    }
    // Drain deferred pairs on the final round — Lombok's AST patches are guaranteed to have fired
    // by now. Merge the held configs into configsByPair so generate(...) can find them via the
    // existing lookup path. The inDeferredDrain flag tells sub-pair discovery inside generate(...)
    // to skip the Lombok-deferral check (we're already in the final pass).
    if (roundEnv.processingOver() && !deferredPairs.isEmpty()) {
      configsByPair.putAll(deferredConfigs);
      final Deque<TypePair> deferredPending = new ArrayDeque<>(deferredPairs);
      final Set<TypePair> deferredSeen = new HashSet<>(deferredPairs);
      final Set<TypePair> deferredUserDeclared = new HashSet<>(deferredPairs);
      inDeferredDrain = true;
      try {
        while (!deferredPending.isEmpty()) {
          final var pair = deferredPending.poll();
          final var sourceEl = processingEnv.getElementUtils().getTypeElement(pair.sourceFq());
          final var targetEl = processingEnv.getElementUtils().getTypeElement(pair.targetFq());
          if (sourceEl == null || targetEl == null) continue;
          generate(sourceEl, targetEl, deferredPending, deferredSeen, deferredUserDeclared);
        }
      } finally {
        inDeferredDrain = false;
      }
      deferredPairs.clear();
      deferredConfigs.clear();
    }
    // Reset accumulated state at the end of processing so a hypothetical second compilation in
    // the same processor instance starts clean. JSR-269 normally creates fresh instances per
    // compilation, but the defensive clear costs nothing.
    if (roundEnv.processingOver()) {
      writeBridgeServices();
      multiTargetSources.clear();
      configsByPair.clear();
      lenientPairs.clear();
      bridgeProviders.clear();
    }
    return true;
  }

  /**
   * Returns {@code true} when a sub-pair discovered during {@link #generate} (or its sealed / field
   * / element variants) must be routed to {@link #deferredPairs} instead of the eager pending
   * queue. Sub-pairs whose source OR target carries a Lombok-synthesizing annotation must wait for
   * {@code processingOver()} so Lombok's AST patches have fired before {@code
   * Elements.getAllMembers} is queried — same rationale as the top-level deferral. When the
   * processor is already inside the deferred drain ({@link #inDeferredDrain} is {@code true}), the
   * check is skipped: we ARE the final pass.
   */
  private boolean shouldDeferSubPair(final TypeElement subSource, final TypeElement subTarget) {
    if (inDeferredDrain) return false;
    return carriesLombokTrigger(subSource) || carriesLombokTrigger(subTarget);
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

  /**
   * Read the raw {@code value} entry from a {@code @Bridge} annotation mirror. Returns a {@link
   * TypeMirror} when the target class is resolvable from the compilation unit, a {@link String}
   * (the target FQN) when it isn't (the fallback shape javac uses for {@code Class<?>} annotation
   * values it can't bind), or {@code null} when the annotation has no {@code value} attribute.
   * Callers are responsible for dispatching on the returned shape — the String fallback fires when
   * {@code @Bridge(SomeClass.class)} references a type in an unresolvable module.
   */
  private Object rawTargetValueFromMirror(final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals("value")) return entry.getValue().getValue();
    }
    return null;
  }

  /**
   * Read the {@code source = ...} carrier-form attribute. Returns the {@code TypeMirror} of the
   * declared source class, {@code null} if the attribute was left at its {@code Void.class} default
   * (i.e. model-anchored form), or a {@code String} FQN when the named class isn't resolvable from
   * this compilation unit (matching {@link #rawTargetValueFromMirror}'s shape so the
   * unresolvable-class diagnostic is uniform).
   */
  private Object rawSourceValueFromMirror(final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals("source")) return entry.getValue().getValue();
    }
    return null;
  }

  /**
   * Read the {@code target = ...} carrier-form attribute. Same shape semantics as {@link
   * #rawSourceValueFromMirror}. Paired with {@code source = ...} on a carrier class; ignored when
   * the model-anchored form is in use (the target comes from {@code value = ...} in that case).
   */
  private Object rawCarrierTargetFromMirror(final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals("target")) return entry.getValue().getValue();
    }
    return null;
  }

  /**
   * True when the type mirror is the {@code Void.class} sentinel used as the carrier-form default.
   */
  private boolean isVoidSentinel(final Object rawValue) {
    if (!(rawValue instanceof TypeMirror tm)) return false;
    if (tm.getKind() != TypeKind.DECLARED) return false;
    final var el = ((DeclaredType) tm).asElement();
    return el instanceof TypeElement te && te.getQualifiedName().contentEquals("java.lang.Void");
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

  // Reads @Bridge(lenient = true). Default false preserves the historical strict-bijection
  // semantics: every existing @Bridge user keeps the round-trip safety net unless they opt in.
  private boolean lenientFromMirror(final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals("lenient")) {
        return (Boolean) entry.getValue().getValue();
      }
    }
    return false;
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

  // Result of parsing a @Bridge's transforms list. `byField` carries the source-field → using
  // class FQN map (the long-standing shape); `forwardOnlyFields` carries the subset of source
  // field names whose @Transform was declared with forwardOnly = true or with a non-empty `method`
  // attribute (qualifier dispatch is implicitly forward-only); `methodsByField` carries the
  // qualifier method name when set, empty when the row uses the BridgeFn shape.
  private record TransformSet(
    Map<String, String> byField,
    Set<String> forwardOnlyFields,
    Map<String, String> methodsByField
  ) {
    static TransformSet empty() {
      return new TransformSet(Map.of(), Set.of(), Map.of());
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
      final var methodsByField = new LinkedHashMap<String, String>();
      for (final var av : list) {
        final var transformAm = (AnnotationMirror) av.getValue();
        String field = null;
        TypeMirror using = null;
        Boolean forwardOnly = Boolean.FALSE;
        String method = "";
        for (final var te : transformAm.getElementValues().entrySet()) {
          final var k = te.getKey().getSimpleName().toString();
          if (k.equals("field")) field = (String) te.getValue().getValue();
          else if (k.equals("using")) using = (TypeMirror) te.getValue().getValue();
          else if (k.equals("forwardOnly")) forwardOnly = (Boolean) te.getValue().getValue();
          else if (k.equals("method")) method = (String) te.getValue().getValue();
        }
        if (field == null || field.isEmpty()) {
          error(element, "@Transform requires a non-empty `field` name");
          return null;
        }
        if (using == null || using.getKind() != TypeKind.DECLARED) {
          error(
            element,
            "@Transform `using` must be a class (BridgeFn implementor, or any class when `method` is set)"
          );
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
        // Validate `method` is either empty (legacy BridgeFn shape) or a syntactically clean Java
        // identifier — whitespace, punctuation, or reserved-word inputs would otherwise produce
        // emit-time syntactic garbage at the generated bridge body that javac surfaces with a
        // cryptic "identifier expected" pointing at synthetic source the user never wrote.
        //
        // Diagnostic shape, in order: BLANK > WHITESPACE-PADDED > INVALID-IDENTIFIER > MISSING-
        // METHOD > NON-STATIC. Each level gives the user a precise fix to apply.
        final var hasMethod = method != null && !method.isEmpty();
        if (hasMethod && method.isBlank()) {
          error(element, "@Transform `method` must not be blank (was \"" + method + "\")");
          return null;
        }
        if (hasMethod && !method.equals(method.strip())) {
          error(
            element,
            "@Transform `method` must not have leading/trailing whitespace (was \"" + method + "\"). Trim and retry."
          );
          return null;
        }
        if (hasMethod && !SourceVersion.isIdentifier(method)) {
          error(element, "@Transform `method` must be a valid Java identifier (was \"" + method + "\")");
          return null;
        }
        if (hasMethod) {
          // Validate the named method exists, is static, and takes exactly one argument. Defers the
          // parameter / return type compatibility check to javac at the generated bridge body —
          // that depends on the source/target field types and javac resolves it precisely there.
          // What we catch here: typos, instance methods (would emit static-call syntax against an
          // instance method → cryptic generated-source error), and arity mismatches.
          final var matches = new ArrayList<ExecutableElement>();
          for (final var e : usingEl.getEnclosedElements()) {
            if (!(e instanceof ExecutableElement m)) continue;
            if (!m.getSimpleName().contentEquals(method)) continue;
            if (m.getParameters().size() != 1) continue;
            matches.add(m);
          }
          if (matches.isEmpty()) {
            error(
              element,
              "@Transform `method` not found: " +
                usingEl.getQualifiedName() +
                "." +
                method +
                "(<one arg>) does not exist. Add a public static method with exactly one parameter."
            );
            return null;
          }
          // Pick the first match that is static; report a clear error if none are static.
          ExecutableElement staticMatch = null;
          for (final var m : matches) {
            if (m.getModifiers().contains(Modifier.STATIC)) {
              staticMatch = m;
              break;
            }
          }
          if (staticMatch == null) {
            error(
              element,
              "@Transform `method` " +
                usingEl.getQualifiedName() +
                "." +
                method +
                "(...) exists but is not static. Qualifier dispatch emits a static-method call; mark it `public static`."
            );
            return null;
          }
        }
        result.put(field, usingEl.getQualifiedName().toString());
        // Qualifier dispatch (method != "") is inherently forward-only. forwardOnly = true is the
        // user-explicit form; non-empty method implies the same.
        if (Boolean.TRUE.equals(forwardOnly) || hasMethod) forwardOnlySet.add(field);
        if (hasMethod) methodsByField.put(field, method);
      }
      return new TransformSet(result, forwardOnlySet, methodsByField);
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
    final var sourceFq = source.getQualifiedName().toString();
    final var targetFq = target.getQualifiedName().toString();
    final var thisPair = new TypePair(sourceFq, targetFq);
    final var cfgForEmission = configsByPair.getOrDefault(thisPair, BridgeConfig.EMPTY);
    // Carrier-form (ADR-0007 / Enh 1): when @Bridge(source = X.class, target = Y.class) lives on a
    // third "carrier" class, emission lands in the carrier's package under <Carrier>Bridge — NOT
    // in the source's package. carrierFq is null for the legacy model-anchored form (annotation
    // on the source class itself), in which case package + name derive from the source.
    final var carrierEl =
      cfgForEmission.carrierFq() == null
        ? null
        : processingEnv.getElementUtils().getTypeElement(cfgForEmission.carrierFq());
    final var pkg =
      carrierEl != null
        ? processingEnv.getElementUtils().getPackageOf(carrierEl).getQualifiedName().toString()
        : processingEnv.getElementUtils().getPackageOf(source).getQualifiedName().toString();
    final var useShortName = userDeclared.contains(thisPair) && !multiTargetSources.contains(sourceFq);
    final var bridgeName =
      carrierEl != null ? carrierEl.getSimpleName() + "Bridge" : bridgeClassName(source, target, useShortName);
    final var qualifiedBridge = pkg.isEmpty() ? bridgeName : pkg + "." + bridgeName;

    final var sourceFields = fieldsOf(source);
    final var targetFields = fieldsOf(target);
    final var cfg = configsByPair.getOrDefault(thisPair, BridgeConfig.EMPTY);
    final var drops = cfg.drops();
    final var renames = cfg.renames();
    final var renameFanouts = cfg.renameFanouts();
    final var transforms = cfg.transforms();
    final var forwardOnlyTransforms = cfg.forwardOnlyTransforms();
    final var rawDefaults = cfg.defaults();
    final var viaMappers = cfg.viaMappers();
    final var writeStrategy = cfg.writeStrategy();
    final var rawConstants = cfg.constants();
    final var computes = cfg.computes();
    // A pair is lenient if its own config says so, or if an enclosing lenient @Bridge referenced it
    // as a sub-pair (leniency propagates down the nesting).
    final var lenient = cfg.lenient() || lenientPairs.contains(thisPair);

    // Validate every transform field is a real source field (drops would mask the validation).
    for (final var t : transforms.keySet()) {
      if (sourceFields.stream().noneMatch(f -> f.name().equals(t))) {
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
      if (sourceFields.stream().noneMatch(f -> f.name().equals(v))) {
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
        error(source, "@Bridge field \"" + e.getKey() + "\" appears in both renames and drops — pick one.");
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
      // GAP-5: when @Compute owns target.X and source.X exists with a source-side modifier
      // (@Default / @Transform / @ViaMapper), the source-side annotation becomes dead code —
      // the supplier short-circuits any source read for that slot. Reject loudly so the user
      // either drops the source-side modifier or renames source.X to a different target slot.
      if (sourceNames.contains(fieldName)) {
        if (parsedDefaults.containsKey(fieldName)) {
          error(
            source,
            "@Bridge field \"" +
              fieldName +
              "\" appears in both computes (target slot) and defaults — pick one (compute supplies the slot from a Supplier; the default would never fire)."
          );
          return;
        }
        if (transforms.containsKey(fieldName)) {
          error(
            source,
            "@Bridge field \"" +
              fieldName +
              "\" appears in both computes (target slot) and transforms — pick one (compute supplies the slot from a Supplier; the source-side transform would never be consumed)."
          );
          return;
        }
        if (viaMappers.containsKey(fieldName)) {
          error(
            source,
            "@Bridge field \"" +
              fieldName +
              "\" appears in both computes (target slot) and viaMappers — pick one (compute supplies the slot from a Supplier; the via-mapper would never be invoked)."
          );
          return;
        }
      }
      injectedTargetFields.add(fieldName);
    }

    // @Bridge(lenient = true): auto-fill the mismatch on both sides so the bijection check below
    // passes naturally without forcing the user to declare a drop or constant for every unmatched
    // field. Source fields with no target counterpart → drops. Target fields with no source
    // counterpart → synthesized JLS-default constants. Same-name matches and declared renames pass
    // through unchanged. Produces a partial-Iso: see @Bridge#lenient javadoc for the round-trip
    // warning. Mutates `drops`, `parsedConstants`, and `injectedTargetFields` so the downstream
    // generation code sees a strict-shape config and needs no further plumbing.
    final var effectiveDrops = lenient ? new LinkedHashSet<>(drops) : drops;
    if (lenient) {
      final var targetFieldNames = targetFields.stream().map(Field::name).collect(Collectors.toSet());
      for (final var sf : sourceFields) {
        if (effectiveDrops.contains(sf.name())) continue;
        final var mappedTarget = renames.getOrDefault(sf.name(), sf.name());
        if (!targetFieldNames.contains(mappedTarget)) {
          effectiveDrops.add(sf.name());
        }
      }
      final var sourceMappedTargets = new HashSet<String>();
      for (final var sf : sourceFields) {
        if (effectiveDrops.contains(sf.name())) continue;
        sourceMappedTargets.add(renames.getOrDefault(sf.name(), sf.name()));
        final var extras = renameFanouts.get(sf.name());
        if (extras != null) sourceMappedTargets.addAll(extras);
      }
      for (final var tf : targetFields) {
        final var name = tf.name();
        if (
          injectedTargetFields.contains(name) || renameTargetNames.contains(name) || sourceMappedTargets.contains(name)
        ) continue;
        parsedConstants.put(name, defaultLiteralFor(tf.type()));
        injectedTargetFields.add(name);
      }
    }

    // Bijection check: apply forward renames on the source side, then compare to target names —
    // skipping target names covered by constants/computes (injected; no source counterpart needed).
    // Dropped sources are excluded from the check entirely.
    final var nonDroppedSourceFields = effectiveDrops.isEmpty()
      ? sourceFields
      : sourceFields
          .stream()
          .filter(f -> !effectiveDrops.contains(f.name()))
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
      cfg.transformMethods(),
      viaMappers,
      pending,
      seen,
      userDeclared,
      lenient
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
      if (effectiveDrops.contains(sourceName)) return defaultLiteralFor(fieldByName(sourceFields, sourceName).type());
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
    // Patch emits a sparse overlay: for each source field, read from `partial` (the user's
    // partially-populated target) when the target's component is non-null, else fall back to the
    // corresponding read on `base`. Primitive target components are always treated as
    // patch-present (matching runtime Mapper#patch semantics: a boxed primitive is never null).
    //
    // P5-DBL: each reference-type source slot needs its partial.field() value referenced THREE
    // times (null-check + patch call + @Default outer wrap). Calling partial.<getter>() that many
    // times is safe for record accessors but unsafe for bean getters with side effects (lazy-init,
    // audit logging, counters). Same for SubBridge.patch(...) on RECURSE fields when @Default is
    // configured. We avoid double-evaluation by precomputing one local per source field in a
    // prelude block, then referencing the locals in the conditional. The prelude is collected
    // here as patchLocals (declarations) keyed by source field name.
    final var patchLocals = new LinkedHashMap<String, String>();
    final Function<String, String> readPatch = sourceName -> {
      final var sf = fieldByName(sourceFields, sourceName);
      final var baseRead = readExpr(source, "base", sf);
      if (effectiveDrops.contains(sourceName)) return baseRead;
      if (forwardOnlyTransforms.contains(sourceName)) return baseRead;
      final var tgtName = renames.getOrDefault(sourceName, sourceName);
      final var tf = fieldByName(targetFields, tgtName);
      final var plan = fieldPlans.get(sourceName);
      final var partialReadExpr = readExpr(target, "partial", tf);
      // Primitive target components autobox to non-null wrappers on read — always patch them.
      // No conditional, so no double-eval; just route through applyBackward.
      if (tf.type().getKind().isPrimitive()) return applyBackward(sourceName, plan, partialReadExpr);
      // Reference-type slots: precompute partial.<getter>() once into __pp_<sourceName>.
      final var pLocal = "__pp_" + sourceName;
      patchLocals.put(pLocal, tf.type() + " " + pLocal + " = " + partialReadExpr + ";");
      // P5-3: nested RECURSE plans use SubBridge.patch(base.field, partial.field) — recursive
      // patch all the way down so a sparse partial only overlays non-null sub-components.
      final String partialRead;
      if (plan.kind() == FieldPlan.Kind.RECURSE) {
        partialRead = plan.subBridgeName() + ".patch(" + baseRead + ", " + pLocal + ")";
      } else {
        partialRead = applyBackward(sourceName, plan, pLocal);
      }
      // P5-5 + P5-DBL: when a @Default is configured, the outer null-coalesce needs to
      // reference
      // the conditional's result without re-evaluating the entire ternary. Precompute via a
      // second
      // local __cond_<sourceName> so the @Default check sees a single value.
      final var conditional = "(" + pLocal + " != null ? " + partialRead + " : " + baseRead + ")";
      if (parsedDefaults.containsKey(sourceName)) {
        final var cLocal = "__cond_" + sourceName;
        patchLocals.put(cLocal, sf.type() + " " + cLocal + " = " + conditional + ";");
        return "(" + cLocal + " == null ? " + parsedDefaults.get(sourceName) + " : " + cLocal + ")";
      }
      return conditional;
    };
    final var patchInner = buildExpr(source, readPatch, sourceFields, writeStrategy, source);
    if (patchInner == null) return;
    // Wrap the inner expression in a block prelude carrying the precomputed locals so the final
    // emitted body has the right shape: { final <type> __pp_X = ...; ... return <inner>; }
    final String patchBody;
    if (patchLocals.isEmpty()) {
      patchBody = patchInner;
    } else {
      final var sb = new StringBuilder("{ ");
      for (final var decl : patchLocals.values()) sb.append("final ").append(decl).append(" ");
      // If the inner expression is already a block (POJO writeStrategy path: "{ ...; return out;
      // }"),
      // strip both braces and inline the body so we don't double-close the outer block. The
      // .trim() on the brace check is a safety net: if any future buildExpr emit path prepends
      // whitespace, the brace detection still recognises the block shape rather than silently
      // falling through to the expression branch and generating non-compiling output.
      final var trimmedInner = patchInner.trim();
      if (trimmedInner.startsWith("{") && trimmedInner.endsWith("}")) {
        final var stripped = trimmedInner.substring(1, trimmedInner.length() - 1).trim();
        sb.append(stripped).append(" }");
        patchBody = sb.toString();
      } else {
        sb.append("return ").append(patchInner).append("; }");
        patchBody = sb.toString();
      }
    }

    // Carrier-form bridges live in the carrier's package, unreachable by the source-keyed runtime
    // probe — register a ServiceLoader provider so mapperForward discovers them by (source,
    // target).
    if (carrierEl != null) emitBridgeProvider(source, target, bridgeName, pkg);

    final var imports = new TreeSet<>(importsFor(fieldPlans, sourceFields, targetFields, renames));
    imports.add("io.github.eschizoid.telescope.Telescope");
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
        //
        // Qualifier-dispatch rows (cfg.transformMethods()) skip the singleton declaration — the
        // emitted code calls UsingClass.methodName(...) directly, so no instance is needed.
        if (!transforms.isEmpty()) {
          boolean anyEmitted = false;
          for (final var e : transforms.entrySet()) {
            if (cfg.transformMethods().containsKey(e.getKey())) continue;
            out.println(
              "  private static final " + e.getValue() + " __tx_" + e.getKey() + " = new " + e.getValue() + "();"
            );
            anyEmitted = true;
          }
          if (anyEmitted) out.println();
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
        // Null in -> null out, matching the runtime structural Iso. Also lets a null container
        // element (subBridge.forward(null) in the container helpers) pass through as null.
        out.println("    if (s == null) return null;");
        emitMethodBody(out, forwardBody);
        out.println("  }");
        out.println();
        out.println("  public static " + sourceFq + " backward(final " + targetFq + " t) {");
        out.println("    if (t == null) return null;");
        emitMethodBody(out, backwardBody);
        out.println("  }");
        out.println();
        // Sparse-overlay patch: read non-null fields of `partial` (a partially-populated target)
        // and apply them onto `base`. Mirrors the runtime Mapper#patch(base, partial) semantics.
        // Reference target components null-gate to base; primitive components autobox to non-null
        // and are always overlaid; nested RECURSE plans recursively patch all the way down.
        out.println(
          "  public static " + sourceFq + " patch(final " + sourceFq + " base, final " + targetFq + " partial) {"
        );
        // P5-1: match runtime Mapper#patch's null-guard. Without this guard, a sparse PATCH
        // request that arrives with no body (partial == null) would NPE on the first
        // partial.field() call; runtime returns `base` cleanly in the same situation.
        out.println("    if (base == null || partial == null) return base;");
        emitMethodBody(out, patchBody);
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
          "  /** Directly-callable mapper value — one interface hop, MapStruct's {@code INSTANCE.toRec(s)} cost."
        );
        out.println(
          "   * Call {@code BRIDGE_FN.forward(s)} / {@code .backward(t)} in a hot loop; use {@code BRIDGE} for the"
        );
        out.println("   * composable lattice value. */");
        out.println("  public static final BridgeFn<" + sourceFq + ", " + targetFq + "> BRIDGE_FN = new Fn();");
        out.println(
          "  public static final Telescope<" + sourceFq + ", " + targetFq + "> BRIDGE = Telescope.bridge(BRIDGE_FN);"
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
        final var raw = rawTargetValueFromMirror(bridgeAm);
        if (!(raw instanceof TypeMirror tm) || tm.getKind() != TypeKind.DECLARED) continue;
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
          final var only = rawTargetValueFromMirror(caseBridges.get(0));
          if (!(only instanceof DeclaredType decl)) continue;
          final var onlyEl = (TypeElement) decl.asElement();
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
      // already in the queue; this is idempotent via `seen`. Route Lombok-touching sub-pairs to
      // deferredPairs so they wait for processingOver() like the top-level deferred pairs.
      final var casePair = new TypePair(sourceCaseEl.getQualifiedName().toString(), targetCaseFq);
      if (seen.add(casePair)) {
        if (shouldDeferSubPair(sourceCaseEl, targetCaseEl)) deferredPairs.add(casePair);
        else pending.add(casePair);
      }
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
        "io.github.eschizoid.telescope.Telescope",
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
        // P5-6: sealed-umbrella patch — dispatch each (basePermit, partialPermit) pair to the
        // per-case bridge's patch when both sides land on the matching sealed case.
        //
        // P5-SLD: when the cases DON'T match (e.g. base is a CreditCard, partial is a
        // BankTransferEntity), return `base` unchanged. Patch is an OVERLAY contract: the partial
        // refines the base. If the partial's sealed case is incompatible with the base's case,
        // there is no defined overlay — silently switching the concrete sealed type via a full
        // backward conversion would surprise HTTP-PATCH callers ("PATCH /payments/123 with a
        // bank-transfer body just changed my CreditCard into a BankTransfer"). No-op is the
        // safe contract; callers who genuinely want the case switch should call backward()
        // explicitly.
        out.println(
          "  public static " + sourceFq + " patch(final " + sourceFq + " base, final " + targetFq + " partial) {"
        );
        out.println("    if (base == null || partial == null) return base;");
        for (final var e : entries) {
          final var sCase = e.sourceCase().getQualifiedName();
          final var tCase = e.targetCase().getQualifiedName();
          out.println(
            "    if (base instanceof " +
              sCase +
              " sb && partial instanceof " +
              tCase +
              " tp) return " +
              e.bridgeFq() +
              ".patch(sb, tp);"
          );
        }
        out.println("    return base; // P5-SLD: case mismatch is a no-op, not a type switch.");
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
          "  /** Directly-callable mapper value — one interface hop, MapStruct's {@code INSTANCE.toRec(s)} cost."
        );
        out.println(
          "   * Call {@code BRIDGE_FN.forward(s)} / {@code .backward(t)} in a hot loop; use {@code BRIDGE} for the"
        );
        out.println("   * composable lattice value. */");
        out.println("  public static final BridgeFn<" + sourceFq + ", " + targetFq + "> BRIDGE_FN = new Fn();");
        out.println(
          "  public static final Telescope<" + sourceFq + ", " + targetFq + "> BRIDGE = Telescope.bridge(BRIDGE_FN);"
        );
      }
    );
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
   *   <li>{@code PRIM_WRAPPER} — one side primitive, the other its boxed wrapper; auto box/unbox,
   *       null-coalescing to the primitive's JLS default on the unbox direction.
   *   <li>{@code RECURSE} — scalar sub-pair with both sides reflectable; reference its
   *       forward/backward methods directly.
   *   <li>{@code LIST}, {@code SET}, {@code MAP_VALUES}, {@code OPTIONAL} — same-kind container on
   *       both sides with element types that need a sub-bridge; lift element-wise via stream / map.
   *   <li>{@code OPTIONAL_TO_NULLABLE} — source is {@code Optional<X>}, target is plain (nullable)
   *       {@code Y}; bridge the element and unwrap on forward / wrap on backward.
   *   <li>{@code NULLABLE_TO_OPTIONAL} — mirror direction.
   * </ul>
   *
   * <p>This flat record now carries several components that are live only for a subset of {@link
   * Kind}s (the container-impl and raw-container fields for LIST/SET/MAP_VALUES, the null-default
   * fields for PRIM_WRAPPER, the qualifier for TRANSFORM) — the per-variant validity matrix lives
   * in the field comments, not the type. That is acceptable for this private, single-file plan
   * object, but it is the binding constraint now: do NOT add a further variant-specific component
   * to this flat shape. The next container/variant feature should first convert this to a sealed
   * hierarchy (a Container variant carrying kind/sub/impls/raw, separate PrimWrapper / Recurse /
   * Transform / Identity variants) so the compiler enforces the matrix the comments currently
   * describe.
   */
  private record FieldPlan(
    Kind kind,
    String subBridgeName,
    String qualifierMethod,
    // PRIM_WRAPPER only: the JLS-default literal to coalesce a null read to, per direction, or
    // null
    // when that direction writes the wrapper side (which tolerates null). Mirrors the runtime
    // primitiveWrapperIso's fwdDefault / bwdDefault.
    String fwdNullDefault,
    String bwdNullDefault,
    // LIST/SET/MAP_VALUES only: the simple name of the concrete collection/map class to allocate
    // for
    // the forward (target) and backward (source) outputs, used by the inline identity-element
    // copy
    // in applyForward/applyBackward. Null for non-container kinds (the element-bridging helper
    // path
    // recomputes its allocation from the field types directly). Mirrors DeepMap's allocation
    // table.
    String fwdContainerImpl,
    String bwdContainerImpl,
    // LIST/SET/MAP_VALUES only: true when the field is a raw (non-generic) Collection/Map subtype
    // on
    // at least one side (e.g. `class ImageUrls extends ArrayList<ImageUrl>`, or a generic
    // interface
    // paired with such a subtype). The element type lives in the supertype, the subtype is
    // allocated
    // via its no-arg constructor (subclasses don't inherit the JDK copy ctor), so these route to
    // the
    // self-contained raw helpers instead of the generic copy-ctor inline path.
    boolean rawContainer
  ) {
    enum Kind {
      IDENTITY,
      PRIM_WRAPPER,
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
      return new FieldPlan(Kind.IDENTITY, null, null, null, null, null, null, false);
    }

    // Attach the concrete-impl class names for a LIST/SET/MAP_VALUES plan — the classes the inline
    // identity-element copy allocates for the forward (target) and backward (source) outputs.
    FieldPlan withContainerImpls(final String fwdImpl, final String bwdImpl) {
      return new FieldPlan(
        kind,
        subBridgeName,
        qualifierMethod,
        fwdNullDefault,
        bwdNullDefault,
        fwdImpl,
        bwdImpl,
        false
      );
    }

    // Mark a LIST/SET/MAP_VALUES plan as a raw (non-generic) Collection/Map subtype container, so
    // applyForward/applyBackward route to the self-contained raw helpers (no-arg ctor + addAll /
    // element loop) rather than the generic copy-ctor inline path.
    static FieldPlan rawContainer(final Kind kind, final String subBridgeName) {
      return new FieldPlan(kind, Objects.requireNonNull(subBridgeName), null, null, null, null, null, true);
    }

    // Primitive ↔ boxed wrapper. The direction that writes the primitive side null-coalesces a null
    // read to that primitive's JLS default; the direction that writes the wrapper passes through
    // (null is a legal wrapper value). Exactly one of the two defaults is non-null for any given
    // pair. Matches the runtime DeepMap.primitiveWrapperIso.
    static FieldPlan primWrapper(final String fwdNullDefault, final String bwdNullDefault) {
      return new FieldPlan(Kind.PRIM_WRAPPER, null, null, fwdNullDefault, bwdNullDefault, null, null, false);
    }

    static FieldPlan recurse(final String subBridgeName) {
      return new FieldPlan(Kind.RECURSE, Objects.requireNonNull(subBridgeName), null, null, null, null, null, false);
    }

    static FieldPlan ofKind(final Kind kind, final String subBridgeName) {
      return new FieldPlan(kind, Objects.requireNonNull(subBridgeName), null, null, null, null, null, false);
    }

    // Qualifier-dispatch TRANSFORM variant: the `using` class hosts a named static method, NOT a
    // BridgeFn implementor. Codegen emits a direct {@code UsingClass.methodName(value)} call and
    // does not declare a {@code __tx_<field>} singleton instance.
    static FieldPlan ofTransformQualified(final String usingClassFqn, final String method) {
      return new FieldPlan(
        Kind.TRANSFORM,
        Objects.requireNonNull(usingClassFqn),
        Objects.requireNonNull(method),
        null,
        null,
        null,
        null,
        false
      );
    }
  }

  /**
   * Container shape of a type — mirrors {@code DeepMap.ContainerShape}. {@code null} keyType for
   * non-Map shapes; the keyType on a Map is validated to match across the source/target pair so the
   * lift preserves keys identically.
   */
  private record ContainerShape(FieldPlan.Kind kind, TypeMirror elementType, TypeMirror keyType) {}

  // The container shape of a type, accepting any List/Set/Map SUBTYPE (ArrayList, TreeSet,
  // LinkedHashMap, …) — not just the exact interface — via erasure assignability, matching the
  // runtime ContainerShape's isAssignableFrom check. Optional is final, so it stays an exact match.
  // The declared type's type arguments give the element (and key) types; a raw subtype with no type
  // arguments (e.g. `class Names extends ArrayList<String>`) is not handled here (returns null),
  // same as before.
  private ContainerShape containerShapeOf(final TypeMirror type) {
    if (!(type instanceof DeclaredType dt)) return null;
    final var args = dt.getTypeArguments();
    if (assignableToRaw(type, "java.util.Optional")) {
      return args.size() == 1 ? new ContainerShape(FieldPlan.Kind.OPTIONAL, args.get(0), null) : null;
    }
    if (args.size() == 1 && assignableToRaw(type, "java.util.List")) {
      return new ContainerShape(FieldPlan.Kind.LIST, args.get(0), null);
    }
    if (args.size() == 1 && assignableToRaw(type, "java.util.Set")) {
      return new ContainerShape(FieldPlan.Kind.SET, args.get(0), null);
    }
    if (args.size() == 2 && assignableToRaw(type, "java.util.Map")) {
      return new ContainerShape(FieldPlan.Kind.MAP_VALUES, args.get(1), args.get(0));
    }
    return null;
  }

  // The container shape of a RAW (non-generic) Collection/Map subtype — a field declared as `class
  // ImageUrls extends ArrayList<ImageUrl>` whose own type-argument list is empty, so the element
  // type lives in the supertype. Returns null for a generic container (handled by
  // containerShapeOf),
  // a raw use of a generic type with no concrete supertype element, or a non-container. Optional is
  // final and cannot be subtyped, so it has no raw form.
  private ContainerShape rawContainerShapeOf(final TypeMirror type) {
    if (!(type instanceof DeclaredType dt) || !dt.getTypeArguments().isEmpty()) return null;
    if (assignableToRaw(type, "java.util.List")) {
      final var args = containerViewArgs(type, "java.util.List");
      return args.size() == 1 ? new ContainerShape(FieldPlan.Kind.LIST, args.get(0), null) : null;
    }
    if (assignableToRaw(type, "java.util.Set")) {
      final var args = containerViewArgs(type, "java.util.Set");
      return args.size() == 1 ? new ContainerShape(FieldPlan.Kind.SET, args.get(0), null) : null;
    }
    if (assignableToRaw(type, "java.util.Map")) {
      final var args = containerViewArgs(type, "java.util.Map");
      return args.size() == 2 ? new ContainerShape(FieldPlan.Kind.MAP_VALUES, args.get(1), args.get(0)) : null;
    }
    return null;
  }

  // The type arguments of `type`'s view as the JDK container `rawFqn` (e.g. the `<ImageUrl>` of the
  // `java.util.List` supertype of `class ImageUrls extends ArrayList<ImageUrl>`). Walks the
  // supertype graph until it finds the declared supertype whose erasure is exactly `rawFqn` and
  // carries concrete type arguments. Returns an empty list when there is no such concrete view
  // (e.g. a raw use of a generic type, where the args are unresolved type variables).
  private List<? extends TypeMirror> containerViewArgs(final TypeMirror type, final String rawFqn) {
    final var types = processingEnv.getTypeUtils();
    final var rawEl = processingEnv.getElementUtils().getTypeElement(rawFqn);
    if (rawEl == null) return List.of();
    final var rawErasure = types.erasure(rawEl.asType());
    final Deque<TypeMirror> queue = new ArrayDeque<>();
    final Set<String> seen = new HashSet<>();
    queue.add(type);
    while (!queue.isEmpty()) {
      final var t = queue.poll();
      if (t instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) {
        if (!seen.add(te.getQualifiedName().toString())) continue;
        if (types.isSameType(types.erasure(t), rawErasure) && !dt.getTypeArguments().isEmpty()) {
          return dt.getTypeArguments();
        }
      }
      queue.addAll(types.directSupertypes(t));
    }
    return List.of();
  }

  // True when `type`'s erasure is assignable to the raw interface named by `rawFqn`.
  private boolean assignableToRaw(final TypeMirror type, final String rawFqn) {
    final var types = processingEnv.getTypeUtils();
    final var raw = processingEnv.getElementUtils().getTypeElement(rawFqn);
    if (raw == null) return false;
    return types.isAssignable(types.erasure(type), types.erasure(raw.asType()));
  }

  // FQN of a container's declared raw class (e.g. java.util.List, java.util.LinkedList) — the type
  // a
  // helper must declare as its return so the rebuild assigns it to the target field directly.
  private static String containerRawFqn(final TypeMirror container) {
    return ((TypeElement) ((DeclaredType) container).asElement()).getQualifiedName().toString();
  }

  // FQN of the concrete, instantiable class to allocate for a container field of the given declared
  // type — the declared subtype itself when it is an instantiable class (ArrayList, TreeSet,
  // TreeMap, …), else the default impl for the interface family. The interface-family defaults
  // match
  // the runtime DeepMap allocators for the bare interface raws: List → ArrayList
  // (listAllocatorFor),
  // Set → LinkedHashSet (setAllocatorFor), Map → HashMap (mapAllocatorFor), so codegen and the
  // reflective path produce the same runtime class for an interface-typed field.
  private static String concreteImplFqn(final TypeMirror container, final FieldPlan.Kind kind) {
    final var el = (TypeElement) ((DeclaredType) container).asElement();
    if (el.getKind() == ElementKind.CLASS && !el.getModifiers().contains(Modifier.ABSTRACT)) {
      return el.getQualifiedName().toString();
    }
    return switch (kind) {
      case LIST -> "java.util.ArrayList";
      case SET -> "java.util.LinkedHashSet";
      case MAP_VALUES -> "java.util.HashMap";
      default -> throw new IllegalStateException("not a collection/map kind: " + kind);
    };
  }

  private static String simpleName(final String fqn) {
    final var dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  // Whether the impl class exposes a capacity-presizing (int) constructor. The default impls do; an
  // arbitrary concrete subtype (LinkedList, TreeSet, TreeMap, …) may not, so it is filled via the
  // no-arg constructor instead.
  private static boolean hasPresizeCtor(final String implFqn) {
    return switch (implFqn) {
      case
        "java.util.ArrayList",
        "java.util.HashSet",
        "java.util.LinkedHashSet",
        "java.util.HashMap",
        "java.util.LinkedHashMap" -> true;
      default -> false;
    };
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
    final Map<String, String> transformMethods,
    final Map<String, String> viaMappers,
    final Deque<TypePair> pending,
    final Set<TypePair> seen,
    final Set<TypePair> userDeclared,
    final boolean lenient
  ) {
    final var plans = new LinkedHashMap<String, FieldPlan>();
    for (final var sf : sourceFields) {
      // Per-field transform supersedes the type-match logic — the transform IS the contract.
      if (transforms.containsKey(sf.name())) {
        final var method = transformMethods.get(sf.name());
        if (method != null && !method.isEmpty()) {
          // Qualifier-dispatch variant: emit a direct UsingClass.methodName(...) call.
          plans.put(sf.name(), FieldPlan.ofTransformQualified(transforms.get(sf.name()), method));
        } else {
          plans.put(sf.name(), FieldPlan.ofKind(FieldPlan.Kind.TRANSFORM, transforms.get(sf.name())));
        }
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
      // (1b) Primitive ↔ its boxed wrapper (boolean↔Boolean, int↔Integer, …). No sub-bridge is
      //      derived (the primitive isn't a declared type and the wrapper isn't
      // telescope-recursable).
      //      The read auto-boxes when it writes the wrapper side and auto-unboxes when it writes
      // the
      //      primitive side; on the unbox direction a null wrapper null-coalesces to that
      // primitive's
      //      JLS default instead of NPE-ing, mirroring the runtime DeepMap.primitiveWrapperIso.
      //      forward writes the target, backward writes the source — so the default applies to
      //      whichever of those is the primitive (primitiveDefaultLiteral is empty for the
      // wrapper).
      if (isPrimitiveWrapperPair(sf.type(), tf.type())) {
        final var fwdNullDefault = primitiveDefaultLiteral(tf.type().getKind()).orElse(null);
        final var bwdNullDefault = primitiveDefaultLiteral(sf.type().getKind()).orElse(null);
        plans.put(sf.name(), FieldPlan.primWrapper(fwdNullDefault, bwdNullDefault));
        continue;
      }
      // (2) Container shape detection — both sides container of the same kind with element types
      //     that need their own sub-bridge. List/Set/Optional/Map values, key-equal Map.
      final var srcShape = containerShapeOf(sf.type());
      final var tgtShape = containerShapeOf(tf.type());
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
          userDeclared,
          lenient
        );
        if (subPlan == null) return null;
        // Attach the concrete-impl class the inline identity-element copy allocates: the target's
        // class on forward, the source's on backward — so a field typed as a concrete subtype
        // (LinkedList, TreeSet, …) is rebuilt as that class, not the default impl.
        final var withImpls = switch (subPlan.kind()) {
          case LIST, SET, MAP_VALUES -> subPlan.withContainerImpls(
            simpleName(concreteImplFqn(tf.type(), subPlan.kind())),
            simpleName(concreteImplFqn(sf.type(), subPlan.kind()))
          );
          default -> subPlan;
        };
        plans.put(sf.name(), withImpls);
        continue;
      }
      // (2-raw) Raw Collection/Map subtype container — at least one side is a non-generic subtype
      //     (`class ImageUrls extends ArrayList<ImageUrl>`), possibly paired with a generic
      //     container on the other. The element lives in the supertype; the subtype is allocated
      // via
      //     its no-arg ctor + element loop. Falls back to the generic shape when a side is already
      // a
      //     parameterized container (the mixed `List<X>` ↔ `Wrap` case).
      final var srcRaw = srcShape != null ? srcShape : rawContainerShapeOf(sf.type());
      final var tgtRaw = tgtShape != null ? tgtShape : rawContainerShapeOf(tf.type());
      if (srcRaw != null && tgtRaw != null && srcRaw.kind() == tgtRaw.kind()) {
        if (srcRaw.kind() == FieldPlan.Kind.MAP_VALUES && !isSameType(srcRaw.keyType(), tgtRaw.keyType())) {
          error(
            source,
            "@Bridge " +
              source.getSimpleName() +
              " -> " +
              target.getSimpleName() +
              ": field '" +
              sf.name() +
              "' has incompatible Map key types — " +
              srcRaw.keyType() +
              " vs " +
              tgtRaw.keyType() +
              ". Map key types must match exactly; codegen preserves source keys."
          );
          return null;
        }
        // The raw helper allocates each side's concrete container via its no-arg constructor. A
        // subtype that hides it (`class Wrap extends ArrayList<X> { Wrap(int cap) {} }`) would make
        // the generated `new Wrap()` fail in the consumer's build with a raw javac error; reject it
        // here with a telescope-authored diagnostic instead.
        final var badAlloc = firstNonAllocatableContainer(sf.type(), tf.type(), srcRaw.kind());
        if (badAlloc != null) {
          error(
            source,
            "@Bridge " +
              source.getSimpleName() +
              " -> " +
              target.getSimpleName() +
              ": field '" +
              sf.name() +
              "' container type '" +
              badAlloc +
              "' has no public no-arg constructor — codegen allocates it directly. Add a no-arg " +
              "constructor, or use the runtime mapper with an explicit row for this field."
          );
          return null;
        }
        final var subPlan = planElementSubBridge(
          source,
          target,
          sf.name(),
          srcRaw.elementType(),
          tgtRaw.elementType(),
          srcRaw.kind(),
          pending,
          seen,
          userDeclared,
          lenient
        );
        if (subPlan == null) return null;
        plans.put(sf.name(), FieldPlan.rawContainer(subPlan.kind(), subPlan.subBridgeName()));
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
          userDeclared,
          lenient
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
          userDeclared,
          lenient
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
        // Sub-pairs whose source or target carries a Lombok-synthesizing annotation must wait for
        // processingOver() — same rationale as the top-level deferral. shouldDeferSubPair returns
        // false while inDeferredDrain is true, so the deferred drain itself doesn't re-defer.
        if (seen.add(subPair)) {
          if (shouldDeferSubPair(subSourceEl, subTargetEl)) deferredPairs.add(subPair);
          else pending.add(subPair);
        }
        // Leniency propagates: a lenient parent's nested sub-pair is itself lenient, so its
        // bijection check is skipped and unmatched nested-target fields take JLS defaults.
        if (lenient) lenientPairs.add(subPair);
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
    final Set<TypePair> userDeclared,
    final boolean lenient
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
      // Container-element sub-pair: same Lombok deferral as the field sub-bridge path above.
      if (seen.add(subPair)) {
        if (shouldDeferSubPair(subSourceEl, subTargetEl)) deferredPairs.add(subPair);
        else pending.add(subPair);
      }
      // Leniency propagates into the element pair too, matching the scalar sub-pair path.
      if (lenient) lenientPairs.add(subPair);
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

  // The concrete-impl class the inline identity-element copy allocates. Non-null for any
  // LIST/SET/MAP_VALUES plan (attached at planning via withContainerImpls); this guard turns a
  // future
  // desync — a container plan reaching emit without its impls — into a loud processor failure
  // rather
  // than emitting `new null<>(...)`.
  private static String requireImpl(final String impl, final String fieldName) {
    return Objects.requireNonNull(impl, "container impl not attached for field '" + fieldName + "'");
  }

  private String applyForward(final String fieldName, final FieldPlan plan, final String readExpr) {
    // Raw Collection/Map subtype containers always route to their self-contained helper (no-arg
    // ctor
    // + addAll / element loop); the inline copy-ctor below is invalid for a non-generic subtype.
    if (plan.rawContainer()) return "__fwd_" + fieldName + "(" + readExpr + ")";
    final var sub = plan.subBridgeName();
    final boolean elementIdentity = IDENTITY_ELEMENT_SENTINEL.equals(sub);
    final var fwdElement = elementIdentity ? "e -> e" : sub + "::forward";
    return switch (plan.kind()) {
      case IDENTITY -> readExpr;
      case PRIM_WRAPPER -> plan.fwdNullDefault() == null
        ? readExpr
        : "(" + readExpr + " == null ? " + plan.fwdNullDefault() + " : " + readExpr + ")";
      case RECURSE -> sub + ".forward(" + readExpr + ")";
      // LIST/SET/MAP_VALUES: when the element type needs a sub-bridge, delegate to a private static
      // helper emitted alongside this method (see emitContainerHelpers below). The helper inlines a
      // size-presized for-loop, eliminating the Stream + Spliterator + collector overhead at the
      // dispatch site. When the element type is identity (same on both sides), a defensive copy is
      // sufficient and we emit it inline.
      case LIST -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          requireImpl(plan.fwdContainerImpl(), fieldName) +
          "<>(" +
          readExpr +
          "))"
        : "__fwd_" + fieldName + "(" + readExpr + ")";
      case SET -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          requireImpl(plan.fwdContainerImpl(), fieldName) +
          "<>(" +
          readExpr +
          "))"
        : "__fwd_" + fieldName + "(" + readExpr + ")";
      case OPTIONAL -> "(" + readExpr + " == null ? null : " + readExpr + ".map(" + fwdElement + "))";
      case MAP_VALUES -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          requireImpl(plan.fwdContainerImpl(), fieldName) +
          "<>(" +
          readExpr +
          "))"
        : "__fwd_" + fieldName + "(" + readExpr + ")";
      case OPTIONAL_TO_NULLABLE -> "(" +
      readExpr +
      " == null ? null : " +
      readExpr +
      ".map(" +
      fwdElement +
      ").orElse(null))";
      case NULLABLE_TO_OPTIONAL -> "Optional.ofNullable(" + readExpr + ").map(" + fwdElement + ")";
      // Qualifier dispatch: emit a direct {@code UsingClass.methodName(value)} call. Otherwise
      // dispatch through the {@code __tx_<field>} BridgeFn singleton instance (legacy shape).
      case TRANSFORM -> plan.qualifierMethod() != null
        ? plan.subBridgeName() + "." + plan.qualifierMethod() + "(" + readExpr + ")"
        : "__tx_" + fieldName + ".forward(" + readExpr + ")";
    };
  }

  private String applyBackward(final String fieldName, final FieldPlan plan, final String readExpr) {
    if (plan.rawContainer()) return "__bwd_" + fieldName + "(" + readExpr + ")";
    final var sub = plan.subBridgeName();
    final boolean elementIdentity = IDENTITY_ELEMENT_SENTINEL.equals(sub);
    final var bwdElement = elementIdentity ? "e -> e" : sub + "::backward";
    return switch (plan.kind()) {
      case IDENTITY -> readExpr;
      case PRIM_WRAPPER -> plan.bwdNullDefault() == null
        ? readExpr
        : "(" + readExpr + " == null ? " + plan.bwdNullDefault() + " : " + readExpr + ")";
      case RECURSE -> sub + ".backward(" + readExpr + ")";
      case LIST -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          requireImpl(plan.bwdContainerImpl(), fieldName) +
          "<>(" +
          readExpr +
          "))"
        : "__bwd_" + fieldName + "(" + readExpr + ")";
      case SET -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          requireImpl(plan.bwdContainerImpl(), fieldName) +
          "<>(" +
          readExpr +
          "))"
        : "__bwd_" + fieldName + "(" + readExpr + ")";
      case OPTIONAL -> "(" + readExpr + " == null ? null : " + readExpr + ".map(" + bwdElement + "))";
      case MAP_VALUES -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          requireImpl(plan.bwdContainerImpl(), fieldName) +
          "<>(" +
          readExpr +
          "))"
        : "__bwd_" + fieldName + "(" + readExpr + ")";
      // For the cross-paradigm bridges, forward and backward are mirror images.
      case OPTIONAL_TO_NULLABLE -> "Optional.ofNullable(" + readExpr + ").map(" + bwdElement + ")";
      case NULLABLE_TO_OPTIONAL -> "(" +
      readExpr +
      " == null ? null : " +
      readExpr +
      ".map(" +
      bwdElement +
      ").orElse(null))";
      case TRANSFORM -> "__tx_" + fieldName + ".backward(" + readExpr + ")";
    };
  }

  /**
   * Compute the {@code java.util.*} imports a bridge needs based on the kinds of fields in its
   * plan. Returned set is fed into {@link AbstractTelescopeProcessor#writeClass(String, String,
   * Set, String, Element, java.util.function.Consumer)} so the emitted file has clean imports
   * instead of FQNs in the body.
   */
  private Set<String> importsFor(
    final Map<String, FieldPlan> fieldPlans,
    final List<Field> sourceFields,
    final List<Field> targetFields,
    final Map<String, String> renames
  ) {
    final var imports = new TreeSet<String>();
    for (final var entry : fieldPlans.entrySet()) {
      final var plan = entry.getValue();
      // Raw-container helpers render every type by fully-qualified name, so they need no imports.
      if (plan.rawContainer()) continue;
      switch (plan.kind()) {
        // A container field needs both the declared raw of each side (the helper return / param
        // types and the inline copy) and the concrete impl each side allocates. For the common
        // interface-typed field this is {List, ArrayList} etc., unchanged; a concrete subtype adds
        // its own class (LinkedList, TreeSet, …).
        case LIST, SET, MAP_VALUES -> {
          final var srcType = fieldByName(sourceFields, entry.getKey()).type();
          final var tgtType = fieldByName(targetFields, renames.getOrDefault(entry.getKey(), entry.getKey())).type();
          for (final var t : List.of(srcType, tgtType)) {
            imports.add(containerRawFqn(t));
            imports.add(concreteImplFqn(t, plan.kind()));
          }
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
      final var srcType = fieldByName(sourceFields, fieldName).type();
      final var tgtType = fieldByName(targetFields, renames.getOrDefault(fieldName, fieldName)).type();
      // Raw Collection/Map subtype containers get the self-contained helper even for identity
      // elements (the inline copy-ctor path is invalid for a non-generic subtype).
      if (plan.rawContainer()) {
        emitRawContainerHelper(out, "__fwd_" + fieldName, srcType, tgtType, plan, "forward");
        emitRawContainerHelper(out, "__bwd_" + fieldName, tgtType, srcType, plan, "backward");
        continue;
      }
      if (IDENTITY_ELEMENT_SENTINEL.equals(plan.subBridgeName())) continue;
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

  // Emit one direction of a raw Collection/Map subtype container helper. Every type is rendered
  // fully-qualified (no imports needed). The output collection is allocated via its no-arg
  // constructor (a Collection/Map subtype does not inherit the JDK copy constructor) and filled by
  // addAll/putAll for an identity element or an element-bridging loop otherwise.
  private void emitRawContainerHelper(
    final PrintWriter out,
    final String name,
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final FieldPlan plan,
    final String direction
  ) {
    final var identity = IDENTITY_ELEMENT_SENTINEL.equals(plan.subBridgeName());
    final var sub = plan.subBridgeName();
    out.println();
    out.println("  private static " + tgtContainer + " " + name + "(final " + srcContainer + " src) {");
    out.println("    if (src == null) return null;");
    out.println("    final var out = " + rawAllocExpr(tgtContainer, plan.kind()) + ";");
    if (plan.kind() == FieldPlan.Kind.MAP_VALUES) {
      if (identity) {
        out.println("    out.putAll(src);");
      } else {
        out.println(
          "    for (final var e : src.entrySet()) out.put(e.getKey(), " + sub + "." + direction + "(e.getValue()));"
        );
      }
    } else if (identity) {
      out.println("    out.addAll(src);");
    } else {
      out.println("    for (final var x : src) out.add(" + sub + "." + direction + "(x));");
    }
    out.println("    return out;");
    out.println("  }");
  }

  // The first of the two raw-container fields whose concrete allocation class lacks a public no-arg
  // constructor (the generated `new <impl>()` would not compile), or null when both are
  // allocatable.
  // The JDK default impls (ArrayList / LinkedHashSet / HashMap) always qualify; only a user subtype
  // can hide its no-arg ctor.
  private String firstNonAllocatableContainer(
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final FieldPlan.Kind kind
  ) {
    for (final var container : List.of(srcContainer, tgtContainer)) {
      final var implFqn = concreteImplFqn(container, kind);
      final var implEl = processingEnv.getElementUtils().getTypeElement(implFqn);
      if (implEl != null && !hasPublicNoArgConstructor(implEl)) return implFqn;
    }
    return null;
  }

  // Allocation expression for a raw-container output: the target's concrete class (the subtype
  // itself
  // when instantiable, else the interface's default impl), with a diamond only when that class is
  // generic. A non-generic subtype (`class ImageUrls extends ArrayList<ImageUrl>`) takes no type
  // arguments; the default impl for a generic interface field takes the field's element args.
  private String rawAllocExpr(final TypeMirror container, final FieldPlan.Kind kind) {
    final var implFqn = concreteImplFqn(container, kind);
    final var implEl = processingEnv.getElementUtils().getTypeElement(implFqn);
    final var generic = implEl != null && !implEl.getTypeParameters().isEmpty();
    if (!generic) return "new " + implFqn + "()";
    if (kind == FieldPlan.Kind.MAP_VALUES) {
      final var args = containerViewArgs(container, "java.util.Map");
      return "new " + implFqn + "<" + args.get(0) + ", " + args.get(1) + ">()";
    }
    final var args = containerViewArgs(container, kind == FieldPlan.Kind.SET ? "java.util.Set" : "java.util.List");
    return "new " + implFqn + "<" + args.get(0) + ">()";
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
    final var returnRaw = simpleName(containerRawFqn(tgtContainer));
    final var paramRaw = simpleName(containerRawFqn(srcContainer));
    final var implFqn = concreteImplFqn(tgtContainer, FieldPlan.Kind.LIST);
    final var alloc =
      "new " + simpleName(implFqn) + "<" + tgtElement + ">" + (hasPresizeCtor(implFqn) ? "(src.size())" : "()");
    out.println();
    out.println(
      "  private static " +
        returnRaw +
        "<" +
        tgtElement +
        "> " +
        name +
        "(final " +
        paramRaw +
        "<" +
        srcElement +
        "> src) {"
    );
    out.println("    if (src == null) return null;");
    out.println("    final var out = " + alloc + ";");
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
    final var returnRaw = simpleName(containerRawFqn(tgtContainer));
    final var paramRaw = simpleName(containerRawFqn(srcContainer));
    final var implFqn = concreteImplFqn(tgtContainer, FieldPlan.Kind.SET);
    final var alloc =
      "new " + simpleName(implFqn) + "<" + tgtElement + ">" + (hasPresizeCtor(implFqn) ? "(src.size())" : "()");
    out.println();
    out.println(
      "  private static " +
        returnRaw +
        "<" +
        tgtElement +
        "> " +
        name +
        "(final " +
        paramRaw +
        "<" +
        srcElement +
        "> src) {"
    );
    out.println("    if (src == null) return null;");
    out.println("    final var out = " + alloc + ";");
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
    final var returnRaw = simpleName(containerRawFqn(tgtContainer));
    final var paramRaw = simpleName(containerRawFqn(srcContainer));
    final var implFqn = concreteImplFqn(tgtContainer, FieldPlan.Kind.MAP_VALUES);
    final var alloc =
      "new " +
      simpleName(implFqn) +
      "<" +
      keyType +
      ", " +
      tgtValue +
      ">" +
      (hasPresizeCtor(implFqn) ? "(src.size())" : "()");
    out.println();
    out.println(
      "  private static " +
        returnRaw +
        "<" +
        keyType +
        ", " +
        tgtValue +
        "> " +
        name +
        "(final " +
        paramRaw +
        "<" +
        keyType +
        ", " +
        srcValue +
        "> src) {"
    );
    out.println("    if (src == null) return null;");
    out.println("    final var out = " + alloc + ";");
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
   * Emit a {@code <Carrier>BridgeProvider implements BridgeProvider} sibling next to the carrier
   * bridge and record it for the {@code META-INF/services} registration written in {@link
   * #writeBridgeServices()}. This is what makes a carrier-form bridge discoverable at runtime: it
   * lives in the carrier's package, so the source-keyed name probe can't find it, but the provider
   * lets {@code mapperForward} look it up by {@code (source, target)} through {@link
   * java.util.ServiceLoader}. The public class has an implicit no-arg constructor for {@code
   * ServiceLoader}; source/target are imported and referenced by simple name like the bridge
   * itself.
   */
  private void emitBridgeProvider(
    final TypeElement source,
    final TypeElement target,
    final String bridgeName,
    final String pkg
  ) {
    final var providerName = bridgeName + "Provider";
    final var qualifiedProvider = pkg.isEmpty() ? providerName : pkg + "." + providerName;
    final var imports = new TreeSet<String>();
    imports.add("io.github.eschizoid.telescope.conversion.BridgeProvider");
    addTypeImport(imports, source, pkg);
    addTypeImport(imports, target, pkg);
    try {
      final var file = processingEnv.getFiler().createSourceFile(qualifiedProvider, source);
      try (final var out = new PrintWriter(file.openWriter())) {
        if (!pkg.isEmpty()) {
          out.println("package " + pkg + ";");
          out.println();
        }
        for (final var imp : imports) out.println("import " + imp + ";");
        out.println();
        out.println(
          "/** Generated by telescope-codegen — registers " + bridgeName + " for runtime @Bridge discovery. */"
        );
        out.println("public final class " + providerName + " implements BridgeProvider {");
        out.println();
        out.println("  @Override");
        out.println("  public Class<?> sourceType() {");
        out.println("    return " + source.getSimpleName() + ".class;");
        out.println("  }");
        out.println();
        out.println("  @Override");
        out.println("  public Class<?> targetType() {");
        out.println("    return " + target.getSimpleName() + ".class;");
        out.println("  }");
        out.println();
        out.println("  @Override");
        out.println("  public Object bridge() {");
        out.println("    return " + bridgeName + ".BRIDGE;");
        out.println("  }");
        out.println("}");
      }
      bridgeProviders.add(qualifiedProvider);
    } catch (final IOException e) {
      error(source, "Failed to write " + qualifiedProvider + ": " + e.getMessage());
    }
  }

  /**
   * Import {@code type} into {@code imports} unless it shares {@code pkg} or lives in {@code
   * java.lang}.
   */
  private void addTypeImport(final TreeSet<String> imports, final TypeElement type, final String pkg) {
    final var fqn = type.getQualifiedName().toString();
    final var typePkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    if (!typePkg.equals(pkg) && !typePkg.equals("java.lang")) imports.add(fqn);
  }

  /**
   * Write the accumulated {@code <Carrier>BridgeProvider} FQNs to {@code
   * META-INF/services/…BridgeProvider} so {@link java.util.ServiceLoader} discovers them on the
   * class path. Written once, in the final round, after every carrier bridge (eager and deferred)
   * has emitted its provider.
   */
  private void writeBridgeServices() {
    if (bridgeProviders.isEmpty()) return;
    final var service = "io.github.eschizoid.telescope.conversion.BridgeProvider";
    try {
      final var file = processingEnv
        .getFiler()
        .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + service);
      try (final var out = new PrintWriter(file.openWriter())) {
        for (final var provider : bridgeProviders) out.println(provider);
      }
    } catch (final IOException e) {
      processingEnv
        .getMessager()
        .printMessage(Diagnostic.Kind.ERROR, "Failed to write bridge ServiceLoader registration: " + e.getMessage());
    }
  }

  /**
   * Whether two TypeMirrors refer to the same type by erasure (handles generics + raw equality).
   */
  private boolean isSameType(final TypeMirror a, final TypeMirror b) {
    return processingEnv.getTypeUtils().isSameType(a, b);
  }

  // True when one of {a, b} is a primitive and the other is exactly its boxed wrapper (boolean ↔
  // Boolean, int ↔ Integer, …). Order-independent. Lets field-bridge planning route such pairs
  // through the PRIM_WRAPPER plan: the box direction passes through, the unbox direction
  // null-defaults a null wrapper to the primitive's JLS default (parity with the runtime
  // primitiveWrapperIso).
  private boolean isPrimitiveWrapperPair(final TypeMirror a, final TypeMirror b) {
    return isBoxedOf(a, b) || isBoxedOf(b, a);
  }

  // True when `prim` is a primitive and `boxed` is exactly its boxed wrapper type.
  private boolean isBoxedOf(final TypeMirror prim, final TypeMirror boxed) {
    if (!prim.getKind().isPrimitive() || !(boxed instanceof DeclaredType)) return false;
    final var types = processingEnv.getTypeUtils();
    return types.isSameType(types.boxedClass((PrimitiveType) prim).asType(), boxed);
  }

  /** Whether the declared type is a record/class telescope can recurse into. */
  private boolean isReflectableDeclared(final DeclaredType dt) {
    final var el = dt.asElement();
    if (!(el instanceof TypeElement te)) return false;
    final var kind = te.getKind();
    if (kind != ElementKind.RECORD && kind != ElementKind.CLASS) return false;
    // Filter out boxed scalars / String / common JDK types we don't want to recurse into.
    final var fq = te.getQualifiedName().toString();
    if (
      fq.startsWith("java.lang.") ||
      fq.startsWith("java.time.") ||
      fq.startsWith("java.util.") ||
      fq.startsWith("java.math.")
    ) {
      return false;
    }
    // A user-package subtype of a JDK Collection/Map (e.g. `class ImageUrls extends
    // ArrayList<ImageUrl>`) clears the prefix filter but must NOT be bean-introspected: ArrayList's
    // synthesized `isEmpty()` reads as a property `empty` with no `setEmpty`, producing a
    // misleading
    // "no setter for 'empty'" error. Same-kind subtype pairs are element-bridged by the (2-raw)
    // container branch before reaching here; this exclusion is the backstop for the pairs that
    // branch can't claim (a kind mismatch like List-subtype vs Set-subtype, or a Collection/Map
    // subtype opposite a non-container), so they fall to the accurate "no auto-bridge could be
    // derived" diagnostic instead of the bean-introspection crash.
    return !assignableToRaw(dt, "java.util.Collection") && !assignableToRaw(dt, "java.util.Map");
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
