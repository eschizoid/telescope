package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.Allocation;
import io.github.eschizoid.telescope.internal.pairing.BeanWriteStrategy;
import io.github.eschizoid.telescope.internal.pairing.ContainerAllocation;
import io.github.eschizoid.telescope.internal.pairing.ContainerView;
import io.github.eschizoid.telescope.internal.pairing.FixedFit;
import io.github.eschizoid.telescope.internal.pairing.Ordering;
import io.github.eschizoid.telescope.internal.pairing.PairDecision;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
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
 * side by an auto-detected strategy: a record via its canonical constructor; a POJO via a static
 * {@code builder()}, then a public constructor whose parameter names match the fields, then a
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
public final class BridgeProcessor extends AbstractTelescopeProcessor {

  /** The shared pairing spec, over the mirror world, consulted for container classification. */
  private PairingRules<TypeMirror> rules;

  /** The shared container allocation rules, over this world's type handles. */
  private ContainerAllocation<TypeMirror> allocation;

  @Override
  public synchronized void init(final ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    final var props = new MirrorProps(processingEnv.getTypeUtils(), processingEnv.getElementUtils());
    rules = new PairingRules<>(props);
    allocation = new ContainerAllocation<>(props);
  }

  /**
   * Public no-arg constructor required by the {@link javax.annotation.processing.Processor} SPI.
   */
  public BridgeProcessor() {
    super();
  }

  private static final String ANNOTATION = "io.github.eschizoid.telescope.annotations.Bridge";
  private static final String BRIDGES_ANNOTATION = "io.github.eschizoid.telescope.annotations.Bridges";
  private static final String BRIDGE_FN_FQN = "io.github.eschizoid.telescope.conversion.BridgeFn";

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
    // Carrier-form emission override. When set, the generated bridge class
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

    /**
     * Stands in for a declared pair whose {@code @Bridge} cannot be read until a later round. It
     * declares the pair and carries its carrier, which decide the name its bridge is written under.
     */
    static BridgeConfig placeholder(final TypeElement carrierEl) {
      return new BridgeConfig(
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
        carrierEl != null ? carrierEl.getQualifiedName().toString() : null,
        false
      );
    }
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
  // is reached from both a lenient and a strict parent the single emitted sub-bridge is lenient for
  // both. For unmatched nested fields that is the more permissive answer, which is the one the
  // runtime mapper gives at every nested level. For an enum pair below the sub-pair it is not:
  // the lenient sub-bridge converts a target constant with no source counterpart back to null,
  // where the strict parent's runtime mapper refuses the pair. Each strict reference is therefore
  // recorded (strictReferences), and once every parent is known a strict parent of a sub-pair in
  // this set is refused where an enum pair below it converts forward only
  // (refuseStrictReferencesToLenientPairs).
  private final Set<TypePair> lenientPairs = new HashSet<>();

  /**
   * Qualified names of {@code @Bridge} types read again next round, because a {@code @ViaMapper},
   * {@code @Transform} or {@code @Compute} on them names a class that does not exist yet. javac
   * hands such a class literal to a processor as a {@code String} rather than a {@link TypeMirror},
   * and re-attributes every source between rounds, so a class another round writes resolves when
   * the type is looked up again.
   */
  private final Set<String> viaMapperRetries = new LinkedHashSet<>();

  // Set true around the deferred drain in processingOver() so sub-pair discovery inside
  // generate(...) / generateSealed(...) / planFieldSubBridges(...) / planElementSubBridge(...)
  // doesn't re-defer (we're ALREADY in the final pass — Lombok is patched).
  private boolean inDeferredDrain = false;

  // FQNs of generated <Carrier>BridgeProvider classes. A carrier-form bridge lives in the carrier's
  // package, which the source-keyed runtime probe can't reach — so each is registered as a
  // ServiceLoader provider (written to META-INF/services in processingOver()) for mapperForward's
  // package-agnostic registry. Cleared after the write so a reused instance starts clean.
  private final Set<String> bridgeProviders = new LinkedHashSet<>();

  /**
   * Which pair claimed each generated bridge FQN. Auto-derived names are built from the two simple
   * names, so two pairs emitting into the same package from same-simple-named types land on one FQN
   * — the second write is a FilerException reported against whichever file the Filer was given,
   * which need not be one the author annotated. Holding the first claimant lets the clash be
   * reported against the pair that caused it.
   *
   * <p>The sibling case — pairs in different packages deriving the same simple name — yields
   * distinct FQNs, so nothing collides here; it is resolved where the parent references them, in
   * {@link #subBridgeReference}.
   */
  private final Map<String, TypePair> bridgeNameOwner = new HashMap<>();

  /** The types each type can reach through its properties, by qualified name; see reachableFrom. */
  private final Map<String, Set<String>> reachable = new HashMap<>();

  /**
   * The sub-bridges and nested element classes of the pair being planned that take the active path,
   * by the name the bridge calls them by. Filled while the pair is planned, read while it is
   * emitted.
   */
  private final Set<String> threadedRefs = new HashSet<>();

  /**
   * For a pair of sealed interfaces the processor plans a bridge for because a field or element of
   * another bridge holds it, the source of the first such bridge, which a refusal is reported on.
   */
  private final Map<TypePair, TypeElement> sealedSubPairParents = new HashMap<>();

  /**
   * The expression passed as the active path to a call that takes one, or {@code null} while
   * emitting code that passes none. A guarded bridge's {@code forward} and {@code backward} pass
   * their own path; its {@code patch} passes a fresh one per call.
   */
  private String pathArg;

  /**
   * The bridge being emitted, which a constructor argument names to reach its {@code __erased}
   * helper, and whether one did; reset for each bridge.
   */
  private String erasingBridge;

  private boolean erasedArgumentUsed;

  /**
   * The primitives whose wrapper a result of the bridge being emitted is null-coalesced from
   * through its {@code __orElse} helper; reset for each bridge.
   */
  private final Set<TypeKind> orElseKinds = EnumSet.noneOf(TypeKind.class);

  /**
   * Per source property of the bridge being emitted, the check its transform's {@code forward}
   * argument takes before the call, where the transform takes a class narrower than the property's
   * type variable ({@link #readsByCheck}); reset for each bridge.
   */
  private final Map<String, ArgumentCheck> forwardArguments = new HashMap<>();

  /** The same per source property, for its transform's {@code backward}, which reads the target. */
  private final Map<String, ArgumentCheck> backwardArguments = new HashMap<>();

  /**
   * While {@code patch} is emitted, the setter writes of a property whose result a null skips, by
   * source property: written as a statement of their own, since an expression cannot skip a write.
   * Empty otherwise.
   */
  private Map<String, PatchSplit> patchSplits = Map.of();

  /** While {@code patch} is emitted, whether the source is rebuilt through its setters. */
  private boolean patchSetters;

  /** While {@code patch} is emitted, the source it rebuilds. */
  private TypeElement patchOwner;

  /**
   * A setter write {@code patch} splits: {@code guard} is null where the partial slot is, and then
   * {@code base} is written; otherwise {@code partial}, the converted value of type {@code gives},
   * is written unless it is null.
   */
  private record PatchSplit(String guard, String partial, String base, TypeMirror gives) {}

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

    // Collect annotated elements from both @Bridge (single use) and @Bridges (the container that
    // javac wraps multiple @Bridge into when the user declares more than one on the same type).
    final var elements = new LinkedHashSet<Element>(roundEnv.getElementsAnnotatedWith(anno));
    if (bridgesAnno != null) elements.addAll(roundEnv.getElementsAnnotatedWith(bridgesAnno));
    for (final var retried : viaMapperRetries) {
      final var retriedEl = processingEnv.getElementUtils().getTypeElement(retried);
      if (retriedEl != null) elements.add(retriedEl);
    }
    viaMapperRetries.clear();

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
        // to anything other than its `Void.class` default sentinel.
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
              "@Bridge: source = ... requires target = ... on the same annotation (carrier form" + " needs both sides)."
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
          sourceMirror = element.asType();
        }
        final var sourceFq = carrierForm
          ? ((TypeElement) ((DeclaredType) sourceMirror).asElement()).getQualifiedName().toString()
          : ((TypeElement) element).getQualifiedName().toString();
        final var targetEl = (TypeElement) ((DeclaredType) targetMirror).asElement();
        if (targetEl.getNestingKind() != NestingKind.TOP_LEVEL) {
          error(element, "@Bridge target must be a top-level type");
          continue;
        }
        // An interface is a declared type, so it reaches here like any other; without this it pairs
        // against an empty component list and is reported as a bijection failure, which describes
        // the symptom and not the cause. Only a sealed one is a target: it names its permits, and
        // each permit is what carries components and a constructor.
        if (targetEl.getKind() == ElementKind.INTERFACE && !targetEl.getModifiers().contains(Modifier.SEALED)) {
          error(
            element,
            "@Bridge target " +
              targetEl.getSimpleName() +
              " is an interface with no permits. A plain interface has no components to pair" +
              " and no constructor to rebuild — name the implementation type instead, or seal" +
              " the interface and give each permit its own @Bridge."
          );
          continue;
        }
        final TypeElement sourceEl = carrierForm
          ? (TypeElement) ((DeclaredType) sourceMirror).asElement()
          : (TypeElement) element;
        if (sourceEl.getNestingKind() != NestingKind.TOP_LEVEL) {
          error(element, "@Bridge source must be a top-level type");
          continue;
        }
        // An abstract class reaches here as an ordinary CLASS, so the kind check above lets it
        // through and the rebuild emits a constructor call javac refuses. Both sides rebuild — the
        // target on forward, the source on backward — so both are checked.
        final var abstractDeclared = firstAbstractSide(sourceEl, targetEl);
        if (abstractDeclared != null) {
          error(
            element,
            "@Bridge " +
              (abstractDeclared == sourceEl ? "source " : "target ") +
              abstractDeclared.getSimpleName() +
              " is abstract, so the rebuild has no constructor to call. Name a concrete" +
              " subtype, or seal it and give each permit its own @Bridge."
          );
          continue;
        }
        final var pair = new TypePair(sourceFq, targetEl.getQualifiedName().toString());
        // The pair is registered and seen while it waits, so a parent reaching it this round names
        // it as its @Bridge will, and does not write it with no config. The placeholder declares
        // the pair and carries its carrier, the two things bridgeNameOf reads; the next round's
        // parse replaces it.
        final var unresolved = unresolvedUsing(bridgeAm);
        if (unresolved != null) {
          if (!roundEnv.processingOver()) {
            viaMapperRetries.add(((TypeElement) element).getQualifiedName().toString());
            seen.add(pair);
            configsByPair.putIfAbsent(pair, BridgeConfig.placeholder(carrierEl));
          } else {
            processingEnv
              .getMessager()
              .printMessage(
                Diagnostic.Kind.ERROR,
                unresolvedUsingMessage(sourceEl, unresolved),
                element,
                unresolved.row()
              );
          }
          continue;
        }
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
      generate(sourceEl, targetEl, pending, seen);
    }
    // Drain deferred pairs on the final round — Lombok's AST patches are guaranteed to have fired
    // by now. Merge the held configs into configsByPair so generate(...) can find them via the
    // existing lookup path. The inDeferredDrain flag tells sub-pair discovery inside generate(...)
    // to skip the Lombok-deferral check (we're already in the final pass).
    if (roundEnv.processingOver() && !deferredPairs.isEmpty()) {
      configsByPair.putAll(deferredConfigs);
      final Deque<TypePair> deferredPending = new ArrayDeque<>(deferredPairs);
      final Set<TypePair> deferredSeen = new HashSet<>(deferredPairs);
      inDeferredDrain = true;
      try {
        while (!deferredPending.isEmpty()) {
          final var pair = deferredPending.poll();
          final var sourceEl = processingEnv.getElementUtils().getTypeElement(pair.sourceFq());
          final var targetEl = processingEnv.getElementUtils().getTypeElement(pair.targetFq());
          if (sourceEl == null || targetEl == null) continue;
          generate(sourceEl, targetEl, deferredPending, deferredSeen);
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
      refuseStrictReferencesToLenientPairs();
      strictReferences.clear();
      writeBridgeServices();
      multiTargetSources.clear();
      configsByPair.clear();
      lenientPairs.clear();
      bridgeProviders.clear();
      bridgeNameOwner.clear();
      reachable.clear();
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
          switch (k) {
            case "source" -> src = (String) re.getValue().getValue();
            case "target" -> tgt = (String) re.getValue().getValue();
            case "forwardOnly" -> forwardOnly = (Boolean) re.getValue().getValue();
            default -> {
            }
          }
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
                "\" appears twice in the renames list — set forwardOnly = true on every" +
                " conflicting entry to opt into forward-only fan-out"
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
          switch (k) {
            case "field" -> field = (String) te.getValue().getValue();
            case "using" -> using = te.getValue().getValue() instanceof TypeMirror tm ? tm : null;
            case "forwardOnly" -> forwardOnly = (Boolean) te.getValue().getValue();
            case "method" -> method = (String) te.getValue().getValue();
            default -> {
            }
          }
        }
        if (field == null || field.isEmpty()) {
          error(element, "@Transform requires a non-empty `field` name");
          return null;
        }
        if (using == null || using.getKind() != TypeKind.DECLARED) {
          error(
            element,
            "@Transform `using` must be a class (BridgeFn implementor, or any class when `method`" + " is set)"
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
                "(<one arg>) does not exist. Add a public static method with exactly one" +
                " parameter."
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
                "(...) exists but is not static. Qualifier dispatch emits a static-method" +
                " call; mark it `public static`."
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

  /**
   * A {@code @ViaMapper}, {@code @Transform} or {@code @Compute} row whose {@code using} names a
   * class that does not resolve. javac hands the processor no name for such a class, only a
   * placeholder, so the annotation and its field are what identify it.
   */
  private record UnresolvedUsing(AnnotationMirror row, String annotation, String field) {}

  /** The {@code @Bridge} attributes whose rows name a class in {@code using}, by row annotation. */
  private static final Map<String, String> ROWS_WITH_USING = Map.of(
    "viaMappers",
    "@ViaMapper",
    "transforms",
    "@Transform",
    "computes",
    "@Compute"
  );

  /**
   * The first row on {@code am} whose {@code using} class does not resolve, or {@code null} when
   * every one does. An unresolvable class literal reaches a processor as a {@code String}, or as a
   * type of kind {@link TypeKind#ERROR}, instead of a declared type.
   */
  private UnresolvedUsing unresolvedUsing(final AnnotationMirror am) {
    for (final var entry : am.getElementValues().entrySet()) {
      final var annotation = ROWS_WITH_USING.get(entry.getKey().getSimpleName().toString());
      if (annotation == null || !(entry.getValue().getValue() instanceof List<?> list)) continue;
      for (final var item : list) {
        if (!(item instanceof AnnotationValue av) || !(av.getValue() instanceof AnnotationMirror row)) continue;
        String field = "";
        AnnotationValue using = null;
        for (final var ve : row.getElementValues().entrySet()) {
          final var k = ve.getKey().getSimpleName().toString();
          if (k.equals("field") && ve.getValue().getValue() instanceof String f) field = f;
          else if (k.equals("using")) using = ve.getValue();
        }
        if (using == null) continue;
        final var value = using.getValue();
        final var resolved = value instanceof TypeMirror tm && tm.getKind() != TypeKind.ERROR;
        if (!resolved) return new UnresolvedUsing(row, annotation, field);
      }
    }
    return null;
  }

  /**
   * Why {@code unresolved} still names no class in the final round. A source field whose type is
   * the source of a pair held back for Lombok has a bridge, but the final round's drain writes it
   * after every {@code @Bridge} has been read, and nothing in the same module can name it.
   */
  private String unresolvedUsingMessage(final TypeElement source, final UnresolvedUsing unresolved) {
    final var head = unresolved.annotation() + "(field = \"" + unresolved.field() + "\") names a `using` class";
    if (!unresolved.annotation().equals("@Compute")) {
      final var fieldType = fieldsOf(source)
        .stream()
        .filter(f -> f.name().equals(unresolved.field()))
        .map(f -> processingEnv.getTypeUtils().erasure(f.type()))
        .filter(t -> t.getKind() == TypeKind.DECLARED)
        .map(t -> ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().toString())
        .findFirst()
        .orElse(null);
      final var elements = processingEnv.getElementUtils();
      for (final var deferred : deferredPairs) {
        if (!deferred.sourceFq().equals(fieldType)) continue;
        final var deferredSource = elements.getTypeElement(deferred.sourceFq());
        final var deferredTarget = elements.getTypeElement(deferred.targetFq());
        if (deferredSource == null || deferredTarget == null) continue;
        return (
          head +
          " that does not exist when this @Bridge is read. If it is " +
          bridgeNameOf(deferredSource, deferredTarget).qualified() +
          ", that bridge is written only in the final round, because its pair carries a Lombok" +
          " annotation, and no code in the same module can name it. Drop the row and let the" +
          " nested pair convert through its own bridge, or declare that pair in another module."
        );
      }
    }
    return (
      head +
      " that does not exist: not in this compilation, not on the classpath, and not among the" +
      " bridges @Bridge generates. Check the name in `using`, or declare the @Bridge that" +
      " generates it."
    );
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
          else if (k.equals("using") && ve.getValue().getValue() instanceof TypeMirror tm) using = tm;
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
          else if (k.equals("using") && ce.getValue().getValue() instanceof TypeMirror tm) using = tm;
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

  // Parse an annotation's string value against a field's declared type. Returns the Java-source
  // literal expression to emit at the field's ctor-arg position, or null when the value can't be
  // represented at that type (the caller already reported via error()). Both @Constant and @Default
  // carry a string value parsed this way, so the caller passes its own name for the diagnostic:
  // naming the wrong one sends the reader to an annotation their source does not contain.
  private String parseConstantLiteral(
    final Element origin,
    final String annotation,
    final String fieldName,
    final String value,
    final TypeMirror type
  ) {
    final var kind = type.getKind();
    if (kind == TypeKind.DECLARED) {
      final var fqn = ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
      if ("null".equals(value)) return "null";
      switch (fqn) {
        case "java.lang.String" -> {
          return "\"" + escapeJavaString(value) + "\"";
        }
        case "java.lang.Boolean" -> {
          return parseBooleanOrError(origin, annotation, fieldName, value, "Boolean");
        }
        case "java.lang.Integer" -> {
          return parseIntegralOrError(origin, annotation, fieldName, value, "Integer", "");
        }
        case "java.lang.Long" -> {
          return parseIntegralOrError(origin, annotation, fieldName, value, "Long", "L");
        }
        case "java.lang.Short" -> {
          return castIntegralOrError(origin, annotation, fieldName, value, "Short", "short");
        }
        case "java.lang.Byte" -> {
          return castIntegralOrError(origin, annotation, fieldName, value, "Byte", "byte");
        }
        case "java.lang.Double" -> {
          return parseFloatingOrError(origin, annotation, fieldName, value, "Double", "");
        }
        case "java.lang.Float" -> {
          return parseFloatingOrError(origin, annotation, fieldName, value, "Float", "f");
        }
        case "java.lang.Character" -> {
          return parseCharOrError(origin, annotation, fieldName, value);
        }
        default -> {
        }
      }
    }
    if (kind.isPrimitive()) {
      return switch (kind) {
        case BOOLEAN -> parseBooleanOrError(origin, annotation, fieldName, value, "boolean");
        case INT -> parseIntegralOrError(origin, annotation, fieldName, value, "int", "");
        case LONG -> parseIntegralOrError(origin, annotation, fieldName, value, "long", "L");
        case SHORT -> castIntegralOrError(origin, annotation, fieldName, value, "short", "short");
        case BYTE -> castIntegralOrError(origin, annotation, fieldName, value, "byte", "byte");
        case DOUBLE -> parseFloatingOrError(origin, annotation, fieldName, value, "double", "");
        case FLOAT -> parseFloatingOrError(origin, annotation, fieldName, value, "float", "f");
        case CHAR -> parseCharOrError(origin, annotation, fieldName, value);
        default -> null;
      };
    }
    error(
      origin,
      annotation +
        " value cannot be parsed at the target field \"" +
        fieldName +
        "\" of type " +
        type +
        " — supported types are String, primitives and their boxed equivalents, and the" +
        " literal \"null\" for reference types"
    );
    return null;
  }

  private String parseBooleanOrError(
    final Element origin,
    final String annotation,
    final String fieldName,
    final String value,
    final String displayType
  ) {
    if ("true".equals(value)) return "true";
    if ("false".equals(value)) return "false";
    error(
      origin,
      annotation + " value=\"" + value + "\" is not a valid " + displayType + " literal at field \"" + fieldName + "\""
    );
    return null;
  }

  private String parseIntegralOrError(
    final Element origin,
    final String annotation,
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
        annotation +
          " value=\"" +
          value +
          "\" is not a valid " +
          displayType +
          " literal at field \"" +
          fieldName +
          "\""
      );
      return null;
    }
    return value + suffix;
  }

  private String castIntegralOrError(
    final Element origin,
    final String annotation,
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
        annotation +
          " value=\"" +
          value +
          "\" is not a valid " +
          displayType +
          " literal at field \"" +
          fieldName +
          "\""
      );
      return null;
    }
    return "(" + cast + ") " + value;
  }

  private String parseFloatingOrError(
    final Element origin,
    final String annotation,
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
        annotation +
          " value=\"" +
          value +
          "\" is not a valid " +
          displayType +
          " literal at field \"" +
          fieldName +
          "\""
      );
      return null;
    }
    return value + suffix;
  }

  private String parseCharOrError(
    final Element origin,
    final String annotation,
    final String fieldName,
    final String value
  ) {
    if (value.length() != 1) {
      error(origin, annotation + " value=\"" + value + "\" must be a single character at field \"" + fieldName + "\"");
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

  /**
   * The value written into a slot nothing else fills, as source text.
   *
   * <p>Every emission of this reaches an argument position, and a method invocation permits no
   * narrowing — so {@code byte} and {@code short} need the cast that {@code int} and wider do not.
   * The table that gets this right is inherited, and this asks it rather than keeping a second
   * copy, which puts it under the test that pins the original against the runtime's own defaults.
   */
  private static String defaultLiteralFor(final TypeMirror type) {
    return primitiveDefaultLiteral(type.getKind()).orElse("null");
  }

  private void generate(
    final TypeElement source,
    final TypeElement target,
    final Deque<TypePair> pending,
    final Set<TypePair> seen
  ) {
    pathArg = null;
    forwardArguments.clear();
    backwardArguments.clear();
    if (source.getKind() == ElementKind.INTERFACE) {
      generateSealed(source, target, pending, seen);
      return;
    }
    final var sourceFq = source.getQualifiedName().toString();
    final var targetFq = target.getQualifiedName().toString();
    final var thisPair = new TypePair(sourceFq, targetFq);
    final var cfgForEmission = configsByPair.getOrDefault(thisPair, BridgeConfig.EMPTY);
    // The carrier, when @Bridge(source = X.class, target = Y.class) lives on a third class, and
    // null when the annotation sits on the source. A carrier-form bridge is written beside the
    // carrier, which decides what the bridge can reach, and is registered for runtime lookup.
    final var carrierEl =
      cfgForEmission.carrierFq() == null
        ? null
        : processingEnv.getElementUtils().getTypeElement(cfgForEmission.carrierFq());
    final var bridge = bridgeNameOf(source, target);
    final var pkg = bridge.pkg();
    final var bridgeName = bridge.simple();
    final var qualifiedBridge = bridge.qualified();
    if (!claimBridgeName(bridge, source, target)) return;

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
    // The package the bridge is emitted into: beside the carrier for a carrier-form pair, beside
    // the source otherwise. A member the bridge calls, a constructor included, has to be reachable
    // from there.
    final var bridgePackage = processingEnv.getElementUtils().getPackageOf(carrierEl != null ? carrierEl : source);
    // The rung each side's rebuild will stop at, decided once and consulted by both the check
    // below and the emission further down, so the two cannot answer differently.
    final var targetRebuild = rebuildFor(target, targetFields, writeStrategy, bridgePackage);
    final var sourceRebuild = rebuildFor(source, sourceFields, writeStrategy, bridgePackage);
    final var rawConstants = cfg.constants();
    final var computes = cfg.computes();
    // The properties the bridge converts by a conversion of its own, drops, or fills without
    // reading the source: a runtime mapper looks below the rest only, when it decides whether the
    // bridge may convert this pair for it.
    final var unwalked = Unwalked.of(
      drops,
      transforms.keySet(),
      viaMappers.keySet(),
      rawConstants.keySet(),
      computes.keySet()
    );
    final var unwalkedSource = unwalked.source();
    final var unwalkedTarget = unwalked.target();
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
      // BridgeFn-shape transforms (no `method` qualifier): what the using class's forward and
      // backward actually accept and return must fit the field pair, or the emitted __tx_ call
      // sites reference javac errors inside a generated file the user never wrote. Both
      // directions are checked, and a forward-only row is exempt from the backward half because
      // it emits no backward call.
      //
      // A using class implementing the raw BridgeFn still escapes, and not because its members
      // cannot be resolved -- they can. The block short-circuits before resolution runs when there
      // are no type arguments to read, so nothing reaches the part that would have caught it.
      //
      // Renamed fields are skipped because the target slot is looked up by the source name,
      // which a rename has moved — and the pairing is rejected a few loops down anyway.
      final var txMethod = cfg.transformMethods().get(t);
      if ((txMethod == null || txMethod.isEmpty()) && !renames.containsKey(t)) {
        final var usingEl = processingEnv.getElementUtils().getTypeElement(transforms.get(t));
        final var fn = usingEl == null ? null : bridgeFnInstantiation(usingEl.asType());
        if (fn == null) {
          error(
            source,
            "@Transform field=\"" + t + "\" `using` class " + transforms.get(t) + " does not implement BridgeFn"
          );
          return;
        }
        final var args = fn.getTypeArguments();
        final var sfType = sourceFields
          .stream()
          .filter(f -> f.name().equals(t))
          .findFirst()
          .orElseThrow()
          .type();
        final var tfField = targetFields
          .stream()
          .filter(f -> f.name().equals(t))
          .findFirst()
          .orElse(null);
        // A class implementing the raw BridgeFn has no type arguments to read, but it still
        // declares the two methods the call sites bind to, and those are what the check compares.
        // Requiring arguments here meant the one shape with nothing to fall back on was also the
        // one shape not checked at all.
        if (tfField != null) {
          final var tfType = tfField.type();
          final var types = processingEnv.getTypeUtils();
          // Assignability models the boxing the source read needs and the unboxing-plus-widening
          // the target write needs, so the declared field types are what to compare — boxing
          // either side first would discard the second.
          //
          // forward reads the source field, hands it to A, and stores B into the target field.
          // backward does the same journey in reverse and so needs the other two conversions: the
          // target field into B, and A into the source field. A pair can satisfy one direction and
          // not the other, and a row that only fits forward still emits a backward unless it is
          // declared forward-only — where the mismatch then lands as a raw javac error inside the
          // generated file rather than as a diagnostic here.
          // Compare against the methods the emitted call sites bind to, falling back to the
          // interface arguments only where the using type exposes no concrete override. A
          // covariant override narrows the return type and Java binds the narrow one, so the
          // interface arguments describe a signature the call site does not use.
          //
          // A property typed by its class's type variable is read and written through the raw
          // class, so the call sites see the erasure of its type, and the check compares that.
          final var sfHeld = heldAs(sfType);
          final var tfHeld = heldAs(tfType);
          final var fwdRes = resolvedFn(usingEl, "forward", sfHeld, bridgePackage);
          final var bwdRes = resolvedFn(usingEl, "backward", tfHeld, bridgePackage);
          // An ambiguous call is one Java refuses outright rather than resolving, so neither a
          // bound member nor the interface's arguments describe what the emitted call site does.
          // Only for a direction that is emitted, though: a forward-only row writes a literal
          // where the backward call would go, so how many backward overloads apply to it is not a
          // question anything asks. Refusing on it turns a build that compiles into one that does
          // not, which is the failure this check exists to prevent rather than cause.
          final var forwardOnly = forwardOnlyTransforms.contains(t);
          final var backwardAmbiguous = bwdRes instanceof Resolution.Ambiguous && !forwardOnly;
          if (fwdRes instanceof Resolution.Ambiguous || backwardAmbiguous) {
            final var forward = fwdRes instanceof Resolution.Ambiguous;
            final var amb = (Resolution.Ambiguous) (forward ? fwdRes : bwdRes);
            error(
              source,
              "@Transform field=\"" +
                t +
                "\": " +
                transforms.get(t) +
                "'s " +
                (forward ? "forward" : "backward") +
                " is ambiguous for " +
                (forward ? sfType : tfType) +
                " between (" +
                amb.a() +
                ") and (" +
                amb.b() +
                "). Java refuses such a call rather than choosing, so leave one overload" +
                " applicable."
            );
            continue;
          }
          final var fwd = fwdRes instanceof Resolution.Bound b ? b.member() : null;
          final var bwd = bwdRes instanceof Resolution.Bound b2 ? b2.member() : null;
          // Neither a resolved member nor a type argument describes this direction, so there is
          // nothing to read: the comparisons below would index an empty argument list. Removing
          // this guard does not widen the check, it throws IndexOutOfBoundsException out of the
          // processor. The shapes that reach it -- an abstract class or an interface
          // raw-implementing BridgeFn, or one leaving a direction abstract -- cannot be
          // instantiated as a transform anyway, so emission refuses them a moment later.
          final var typed = args.size() == 2;
          // One skip served both directions, which is wrong for a row that emits only one of them.
          // A forward-only row never reads the backward slots, so their being unreadable is not a
          // reason to skip it -- and skipping took the forward check along, handing the author the
          // javac error inside a generated file that this check exists to replace.
          if ((fwd == null || (!forwardOnly && bwd == null)) && !typed) continue;
          final var fwdAccepts = fwd == null ? erasedBound(args.get(0)) : fwd.getParameterTypes().getFirst();
          final var fwdGives = fwd == null ? erasedBound(args.get(1)) : fwd.getReturnType();
          // Null only where the guard above let a row through without them, which is exactly where
          // nothing reads them.
          final var bwdAccepts =
            bwd != null ? bwd.getParameterTypes().getFirst() : typed ? erasedBound(args.get(1)) : null;
          final var bwdGives = bwd != null ? bwd.getReturnType() : typed ? erasedBound(args.get(0)) : null;

          // A field is read through its getter and written through whichever member the rebuild
          // picks, and those are different types often enough to matter -- a setter declared wider
          // than its getter takes a value the getter does not name, and a builder method declared
          // narrower refuses one it does. So the read sides compare against what the field is
          // declared as, and the store sides against the member forward and backward will write.
          //
          // Patch writes through the same member and is not checked here. What it stores is a
          // conditional between the converted value and the base's own, whose type is the join of
          // the two rather than either -- and this asks only whether the slot takes the converted
          // value. A slot narrower than the getter takes that and still refuses the join, so patch
          // has shapes this accepts and javac does not.
          //
          // The rebuild is asked rather than modelled. Guessing from the setter alone is what makes
          // this wrong in both directions at once: a target whose rebuild goes through a builder or
          // a name-matched constructor would be judged against a setter nothing calls, and the row
          // accepted on the strength of it emits code that does not compile.
          // Backward is the direction that can have nothing to say: a forward-only row whose
          // transform binds no backward member and carries no type arguments stores a value that
          // does not exist, and the guard above lets the row through because the direction it is
          // about does carry. Asking a slot about that hands a null to the resolver, which is a
          // crash inside the processor rather than a refusal from it. Forward has no such arm --
          // past that guard it always has either a bound member or the arguments it required.
          final var targetSlot = heldAs(writeSlotType(target, targetRebuild, t, fwdGives, bridgePackage));
          final var sourceSlot =
            bwdGives == null ? null : heldAs(writeSlotType(source, sourceRebuild, t, bwdGives, bridgePackage));
          final var storesInto = targetSlot == null ? tfHeld : targetSlot;
          final var storesBack = sourceSlot == null ? sfHeld : sourceSlot;
          // A result wider than the parameter of the member the property's type chooses is written
          // through that member after a check of its class, as the runtime writes it.
          // A result assignable only by an unchecked conversion, a class used raw, gives no
          // arguments to show it fits those the member fixes, and is refused as one no cast
          // reaches. Its arguments are held to the member the property's type chooses, which every
          // path writes through, whichever overload javac would bind the result to.
          final var forwardChosen = chosenParameter(target, targetRebuild, t, targetSlot);
          final var backwardChosen = bwdGives == null ? null : chosenParameter(source, sourceRebuild, t, sourceSlot);
          final var forwardStores =
            (types.isAssignable(fwdGives, storesInto) &&
              rules.argumentsFit(fwdGives, storesInto) &&
              (forwardChosen == null || rules.argumentsFit(fwdGives, forwardChosen))) ||
            reachesByCheck(fwdGives, forwardChosen);
          final var backwardStores =
            bwdGives != null &&
            ((types.isAssignable(bwdGives, storesBack) &&
                rules.argumentsFit(bwdGives, storesBack) &&
                (backwardChosen == null || rules.argumentsFit(bwdGives, backwardChosen))) ||
              reachesByCheck(bwdGives, backwardChosen));
          // A property declared with a type variable is handed to a transform taking a class
          // narrower than the variable's bound after a check of its class, as the runtime hands a
          // row typed by the class's type arguments the value it reads.
          final var forwardChecked = readsByCheck(sfType, sfHeld, fwdAccepts);
          final var backwardChecked = !forwardOnly && readsByCheck(tfType, tfHeld, bwdAccepts);
          final var forwardFits = (types.isAssignable(sfHeld, fwdAccepts) || forwardChecked) && forwardStores;
          final var backwardFits =
            forwardOnly || ((types.isAssignable(tfHeld, bwdAccepts) || backwardChecked) && backwardStores);
          if (!forwardFits || !backwardFits) {
            // Name the signature the call site binds to, not the interface's parameterisation.
            // Those differ whenever an overload or an override is what gets bound, and a message
            // reconstructing BridgeFn<A, B> then prints types that all fit each other while the
            // row is refused -- true of the class and useless about the refusal. A raw class has
            // no parameterisation to print at all.
            final var direction = forwardFits ? "backward" : "forward";
            final var accepts = forwardFits ? bwdAccepts : fwdAccepts;
            final var gives = forwardFits ? bwdGives : fwdGives;
            // Where a slot decided the verdict, the message has to name it. Printing only the
            // declared types leaves a refusal that reads as a contradiction -- "does not carry
            // String -> String" -- because the type that actually refused is the member's, and it
            // appears nowhere.
            final var slot = forwardFits ? sourceSlot : targetSlot;
            final var declared = forwardFits ? sfHeld : tfHeld;
            // Named only where the store is what refused. A row can fail on the read alone -- the
            // transform will not take what the getter gives -- and a member that accepts the value
            // perfectly well had no part in that, so printing it points at the wrong thing.
            final var storeRefused = forwardFits ? !backwardStores : !forwardStores;
            final var into =
              !storeRefused || slot == null || types.isSameType(slot, declared)
                ? ""
                : ", and the member it is written through takes " + slot;
            error(
              source,
              "@Transform field=\"" +
                t +
                "\" does not fit " +
                direction +
                ": " +
                transforms.get(t) +
                "'s " +
                direction +
                " resolves to (" +
                accepts +
                ") -> " +
                gives +
                ", which does not carry " +
                (forwardFits
                  ? heldPhrase(tfType, tfHeld) + " -> " + heldPhrase(sfType, sfHeld)
                  : heldPhrase(sfType, sfHeld) + " -> " + heldPhrase(tfType, tfHeld)) +
                into +
                (forwardFits ? ". Add forwardOnly = true if only the forward direction is wanted." : "")
            );
            return;
          }
          if (forwardChecked) forwardArguments.put(t, argumentCheck(fwdAccepts, source, t));
          if (backwardChecked) backwardArguments.put(t, argumentCheck(bwdAccepts, target, t));
        }
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
            "\" appears in both renames and transforms — pick one (the transform consumes the" +
            " source field; the target slot name comes from the bijection, not @Rename)."
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
              ". Forward-only fan-out writes the same source value into every target; all" +
              " targets must share the same type."
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
            " — primitives cannot be null, so the default would never fire. Use the wrapper" +
            " type or rework the source shape."
        );
        return;
      }
      if (drops.contains(fieldName)) {
        error(
          source,
          "@Bridge field \"" +
            fieldName +
            "\" appears in both defaults and drops — pick one (drop discards, default" +
            " substitutes when null)."
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
            "\" appears in both defaults and transforms — pick one (use a transform that" +
            " handles null internally, or remove the default)."
        );
        return;
      }
      final var lit = parseConstantLiteral(source, "@Default", fieldName, e.getValue(), sf.type());
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
      final var lit = parseConstantLiteral(source, "@Constant", fieldName, e.getValue(), tf.type());
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
            "\" appears in both constants and computes — pick one (constants inject a" +
            " literal; computes inject a Supplier result)."
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
              "\" appears in both computes (target slot) and defaults — pick one (compute" +
              " supplies the slot from a Supplier; the default would never fire)."
          );
          return;
        }
        if (transforms.containsKey(fieldName)) {
          error(
            source,
            "@Bridge field \"" +
              fieldName +
              "\" appears in both computes (target slot) and transforms — pick one (compute" +
              " supplies the slot from a Supplier; the source-side transform would never be" +
              " consumed)."
          );
          return;
        }
        if (viaMappers.containsKey(fieldName)) {
          error(
            source,
            "@Bridge field \"" +
              fieldName +
              "\" appears in both computes (target slot) and viaMappers — pick one (compute" +
              " supplies the slot from a Supplier; the via-mapper would never be invoked)."
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
    if (
      !fieldTypesNameable(source, target, nonDroppedSourceFields, targetFields, renames, forwardOnlyTransforms, pkg)
    ) return;
    // The types each pair of properties is planned and read as, decided by the rule the runtime
    // pairs them by, and the casts that reading them through raw classes needs.
    final var ownConversions = new HashSet<>(transforms.keySet());
    ownConversions.addAll(viaMappers.keySet());
    final var viaLifts = viaLifts(source, sourceFields, targetFields, renames, viaMappers, bridgePackage);
    if (viaLifts == null) return;
    final var paired = pairedFields(
      source,
      target,
      sourceFields,
      nonDroppedSourceFields,
      targetFields,
      renames,
      ownConversions,
      viaLifts.keySet()
    );
    if (paired == null) return;
    final var viaCasts = viaCalls(
      source,
      target,
      sourceFields,
      targetFields,
      paired,
      renames,
      viaMappers,
      viaLifts,
      sourceRebuild,
      targetRebuild,
      bridgePackage
    );
    if (viaCasts == null) return;

    // Build per-field "read expression" recipes: identity, sub-pair recursion, or container lift.
    // The reads need to know how to convert each source-field-value into the matching target-field-
    // value (and vice versa). Per-field decisions can also enqueue new TypePairs to emit. Plans are
    // keyed by SOURCE field name; the target side is looked up via the rename map.
    final var fieldPlans = planFields(
      source,
      target,
      paired.source(),
      paired.target(),
      renames,
      transforms,
      cfg.transformMethods(),
      viaMappers,
      viaLifts,
      pending,
      seen,
      lenient,
      pkg
    );
    if (fieldPlans == null) return;
    final var nested = new LinkedHashMap<>(nestedElements);
    // A pair whose two sides can each reach themselves can be handed an object it is already
    // converting. Its forward and backward carry the path of objects being converted and pass it to
    // the sub-bridges on the same cycle; every other pair is emitted with no trace of it.
    final var guarded = guardsCycles(source, target);
    pathArg = guarded ? "__path" : null;
    erasingBridge = bridgeName;
    erasedArgumentUsed = false;
    orElseKinds.clear();

    // readForward is called with TARGET field names (we walk targetFields). Injected targets
    // (constants, computes) take priority — they're forward-only literal/Supplier expressions with
    // no source counterpart. Otherwise reverse-rename to find the matching source field, then use
    // the source name to look up the plan and read the source. When the source field has a
    // @Default, wrap the source read in a (s.x() == null ? <literal> : s.x()) coalesce so the
    // forward direction substitutes the default for null sources.
    // Every source read is hoisted into one `final` local per field, and the conversion
    // expression references the local. Several plan shapes substitute their read expression more
    // than once (a null-guard plus the guarded use, a @Default coalesce), which is only sound when
    // the expression is a plain accessor: a defensive-copy getter would copy twice, a lazy-loading
    // proxy would resolve twice, and a volatile-backed getter can legally return null on the
    // second load after a non-null first — turning the emitted null-guard into an NPE. Reading
    // once into a local makes every downstream duplication a local reference. The patch emitter
    // established the idiom with its __pp_ prelude.
    final var forwardLocals = new LinkedHashMap<String, String>();
    final Function<String, String> readForward = targetName -> {
      if (parsedConstants.containsKey(targetName)) return parsedConstants.get(targetName);
      if (computes.containsKey(targetName)) return "__cp_" + targetName + ".get()";
      final var srcName = reverseRenames.getOrDefault(targetName, targetName);
      final var sf = fieldByName(paired.source(), srcName);
      final var local = "__fs_" + srcName;
      forwardLocals.putIfAbsent(
        local,
        declarable(sf.type()) +
          " " +
          local +
          " = " +
          paired.castInto(paired.sourceReads(), srcName, readExpr(source, "s", sf)) +
          ";"
      );
      final var read = parsedDefaults.containsKey(srcName)
        ? "(" + local + " == null ? " + parsedDefaults.get(srcName) + " : " + local + ")"
        : local;
      final var plan = fieldPlans.get(srcName);
      // A @ViaMapper bridge is user code with no guaranteed null tolerance — auto-derived
      // sub-bridges open with their own null guard, a user class may not — so the call is
      // null-gated here. Both mentions reference the hoisted local, so nothing is re-read.
      //
      // A primitive local is skipped: it cannot hold null, and `int == null` is not legal Java,
      // so emitting the gate there produces a generated file that does not compile.
      if (plan.userSuppliedBridge() && !sf.type().getKind().isPrimitive()) {
        final var handed = paired.castInto(viaCasts.forwardCasts(), srcName, read);
        return "(" + local + " == null ? null : " + applyForward(srcName, plan, handed) + ")";
      }
      return paired.castInto(
        paired.forwardCasts(),
        targetName,
        paired.checkedInto(paired.forwardChecks(), targetName, applyForward(srcName, plan, read))
      );
    };
    // readBackward is called with SOURCE field names. For drops AND forward-only transforms, emit
    // the type's zero value — both mechanisms have no defined backward and the source slot must be
    // filled with something. Otherwise forward-rename the source name to find the matching target
    // field for the read.
    final var backwardLocals = new LinkedHashMap<String, String>();
    final Function<String, String> readBackward = sourceName -> {
      if (effectiveDrops.contains(sourceName)) return defaultLiteralFor(fieldByName(sourceFields, sourceName).type());
      if (forwardOnlyTransforms.contains(sourceName)) return defaultLiteralFor(
        fieldByName(sourceFields, sourceName).type()
      );
      final var tgtName = renames.getOrDefault(sourceName, sourceName);
      final var tf = fieldByName(paired.target(), tgtName);
      final var local = "__bt_" + sourceName;
      backwardLocals.putIfAbsent(
        local,
        declarable(tf.type()) +
          " " +
          local +
          " = " +
          paired.castInto(paired.targetReads(), tgtName, readExpr(target, "t", tf)) +
          ";"
      );
      final var plan = fieldPlans.get(sourceName);
      // Same user-bridge null gate as readForward, skipped for a primitive for the same reason.
      if (plan.userSuppliedBridge() && !tf.type().getKind().isPrimitive()) {
        final var handed = paired.castInto(viaCasts.backwardCasts(), sourceName, local);
        return "(" + local + " == null ? null : " + applyBackward(sourceName, plan, handed) + ")";
      }
      return paired.castInto(
        paired.backwardCasts(),
        sourceName,
        paired.checkedInto(paired.backwardChecks(), sourceName, applyBackward(sourceName, plan, local))
      );
    };

    // Pass `source` as the annotation site so write-strategy errors land at the user's @Bridge
    // declaration rather than at `target` (which may be a third-party POJO with no annotation).
    // A transformed field's value is what its transform returns, which the transform check has
    // already held to the member it is written through; every other value has the field's own type.
    final var transformedTargets = new HashSet<String>();
    for (final var t : transforms.keySet()) transformedTargets.add(renames.getOrDefault(t, t));
    final var transformedSources = new HashSet<>(transforms.keySet());
    transformedSources.removeAll(forwardOnlyTransforms);
    // What each transform hands the setter it writes through, by the field written: the target's
    // field forward, the source's backward. A transform whose result type this cannot read is left
    // out, and its call binds by that result as it is.
    final var forwardGives = new HashMap<String, TypeMirror>();
    final var backwardGives = new HashMap<String, TypeMirror>();
    // Fields whose value reaches the setter as a reference that may be null although the field's
    // own type is primitive: a @ViaMapper result, which the read gates on a null source.
    final var referenceTargets = new HashSet<String>();
    final var referenceSources = new HashSet<String>();
    for (final var entry : fieldPlans.entrySet()) {
      if (!entry.getValue().userSuppliedBridge()) continue;
      final var sourceName = entry.getKey();
      final var targetName = renames.getOrDefault(sourceName, sourceName);
      if (!fieldByName(sourceFields, sourceName).type().getKind().isPrimitive()) referenceTargets.add(targetName);
      final var tf = targetFields
        .stream()
        .filter(f -> f.name().equals(targetName))
        .findFirst()
        .orElse(null);
      if (tf != null && !tf.type().getKind().isPrimitive()) referenceSources.add(sourceName);
    }
    for (final var t : transforms.keySet()) {
      final var usingEl = processingEnv.getElementUtils().getTypeElement(transforms.get(t));
      if (usingEl == null) continue;
      final var targetName = renames.getOrDefault(t, t);
      final var sf = fieldByName(sourceFields, t);
      final var tf = targetFields
        .stream()
        .filter(f -> f.name().equals(targetName))
        .findFirst()
        .orElse(null);
      final var method = cfg.transformMethods().get(t);
      final var qualified = method != null && !method.isEmpty();
      final var forward = transformGives(
        usingEl,
        qualified ? method : "forward",
        heldAs(sf.type()),
        bridgePackage,
        !qualified,
        1
      );
      if (forward != null) forwardGives.put(targetName, forward);
      if (qualified || forwardOnlyTransforms.contains(t) || tf == null) continue;
      final var backward = transformGives(usingEl, "backward", heldAs(tf.type()), bridgePackage, true, 0);
      if (backward != null) backwardGives.put(t, backward);
    }
    // A @ViaMapper's result is written as a transform's is: through the member the property's type
    // chooses, after a check of its class where the mapper's method returns a wider type, and only
    // where its type arguments can be shown to fit that member's; any other result is refused by
    // the mapper's name. One whose mapper converts each element of a container is left as it is.
    for (final var v : viaMappers.keySet()) {
      if (viaLifts.containsKey(v) || !hasField(sourceFields, v)) continue;
      final var mapper = processingEnv.getElementUtils().getTypeElement(viaMappers.get(v));
      if (mapper == null) continue;
      final var targetName = renames.getOrDefault(v, v);
      final var tf = targetFields
        .stream()
        .filter(f -> f.name().equals(targetName))
        .findFirst()
        .orElse(null);
      final var handed = heldAs(fieldByName(sourceFields, v).type());
      // The resolver binds no method whose one parameter is its own type variable, which javac
      // infers from the value handed to it; such a method is held to the same rule, as inferred.
      final var forward =
        resolvedFn(mapper, "forward", handed, bridgePackage) instanceof Resolution.Bound bound
          ? bound
          : inferringFn(mapper, "forward", handed, bridgePackage);
      if (forward instanceof Resolution.Bound bound && !bound.member().getReturnType().getKind().isPrimitive()) {
        final var result = viaResult(
          v,
          mapper,
          "forward",
          bound.member(),
          handed,
          target,
          targetRebuild,
          targetName,
          bridgePackage
        );
        if (result.refusal() != null) {
          error(source, result.refusal());
          return;
        }
        forwardGives.put(targetName, result.gives());
        transformedTargets.add(targetName);
      }
      if (tf == null) continue;
      final var given = heldAs(tf.type());
      final var backward =
        resolvedFn(mapper, "backward", given, bridgePackage) instanceof Resolution.Bound bound
          ? bound
          : inferringFn(mapper, "backward", given, bridgePackage);
      if (backward instanceof Resolution.Bound bound && !bound.member().getReturnType().getKind().isPrimitive()) {
        final var result = viaResult(
          v,
          mapper,
          "backward",
          bound.member(),
          given,
          source,
          sourceRebuild,
          v,
          bridgePackage
        );
        if (result.refusal() != null) {
          error(source, result.refusal());
          return;
        }
        backwardGives.put(v, result.gives());
        transformedSources.add(v);
      }
    }
    final var builtForward = buildExpr(
      target,
      readForward,
      targetFields,
      writeStrategy,
      source,
      targetRebuild,
      pkg,
      transformedTargets,
      forwardGives,
      referenceTargets
    );
    if (builtForward == null) return;
    final var builtBackward = buildExpr(
      source,
      readBackward,
      sourceFields,
      writeStrategy,
      source,
      sourceRebuild,
      pkg,
      transformedSources,
      backwardGives,
      referenceSources
    );
    if (builtBackward == null) return;
    final var forwardBody = withPrelude(forwardLocals, builtForward);
    final var backwardBody = withPrelude(backwardLocals, builtBackward);
    // Patch emits a sparse overlay: for each source field, read from `partial` (the user's
    // partially-populated target) when the target's component is non-null, else fall back to the
    // corresponding read on `base`. Primitive target components are always treated as
    // patch-present (matching runtime Mapper#patch semantics: a boxed primitive is never null).
    //
    // P5-DBL: each reference-type source slot references its partial.field() value twice
    // (null-check + convert call). Calling partial.<getter>() that many times is safe for record
    // accessors but unsafe for bean getters with side effects (lazy-init, audit logging,
    // counters). We avoid double-evaluation by precomputing one local per source field in a
    // prelude block, then referencing the locals in the conditional. The prelude is collected
    // here as patchLocals (declarations) keyed by source field name.
    final var patchLocals = new LinkedHashMap<String, String>();
    final Function<String, String> readPatch = sourceName -> {
      final var sf = fieldByName(sourceFields, sourceName);
      final var baseRead = readExpr(source, "base", sf);
      if (effectiveDrops.contains(sourceName)) return baseRead;
      if (forwardOnlyTransforms.contains(sourceName)) return baseRead;
      final var tgtName = renames.getOrDefault(sourceName, sourceName);
      final var tf = fieldByName(paired.target(), tgtName);
      final var plan = fieldPlans.get(sourceName);
      final var partialReadExpr = paired.castInto(paired.targetReads(), tgtName, readExpr(target, "partial", tf));
      // A wrapper result, or a result typed by its method's own variable, written into a
      // primitive property is coalesced on the partial's side alone, through a null-coalesce
      // or a checked unboxing. The base's own value is read as the primitive it is and never
      // boxed. A setter skips a null result, which an expression cannot, so its write is a
      // statement of its own (patchSplits).
      final var gives = transformedSources.contains(sourceName) ? backwardGives.get(sourceName) : null;
      final var unboxed = sf.type().getKind().isPrimitive() && unboxedThroughCheck(gives, sf.type());
      // Primitive target components autobox to non-null wrappers on read — always patch them
      // from a partial that exists. A null partial overlays nothing, so base supplies every
      // slot.
      if (tf.type().getKind().isPrimitive()) {
        final var converted = applyBackward(sourceName, plan, partialReadExpr);
        if (unboxed) return patchUnboxed(sourceName, "partial", converted, baseRead, gives, sf.type());
        return "(partial == null ? " + baseRead + " : " + converted + ")";
      }
      // Reference-type slots: precompute partial.<getter>() once into __pp_<sourceName>.
      final var pLocal = "__pp_" + sourceName;
      patchLocals.put(
        pLocal,
        declarable(tf.type()) + " " + pLocal + " = partial == null ? null : " + partialReadExpr + ";"
      );
      // A non-null partial slot is written WHOLE: every plan routes through applyBackward, so a
      // nested component converts via the sub-bridge's backward rather than a recursive
      // sub-patch. Mapper#patch documents whole-value writes for nested components, and a
      // sub-patch would both deep-merge and — because its base == null guard returns base —
      // silently drop the partial's value whenever the base slot is null.
      final var partialRead = applyBackward(
        sourceName,
        plan,
        plan.userSuppliedBridge() ? paired.castInto(viaCasts.backwardCasts(), sourceName, pLocal) : pLocal
      );
      final var checkedPartial = paired.checkedInto(paired.backwardChecks(), sourceName, partialRead);
      if (unboxed) {
        return paired.castInto(
          paired.backwardCasts(),
          sourceName,
          patchUnboxed(sourceName, pLocal, checkedPartial, baseRead, gives, sf.type())
        );
      }
      // @Default is a forward-direction null-coalesce and is never applied here: a slot null in
      // both partial and base stays null rather than resurrecting the default on a field the
      // caller never wrote.
      return paired.castInto(
        paired.backwardCasts(),
        sourceName,
        "(" + pLocal + " != null ? " + checkedPartial + " : " + baseRead + ")"
      );
    };
    // The runtime mapper's patch converts each partial slot as a conversion of its own, so each
    // call starts an empty path.
    pathArg = guarded ? FRESH_PATH : null;
    // Patch writes a conditional between the backward result and the base's own value, whose type
    // is the backward result's where the property's type widens to it. Where that result is wider
    // than the setter chosen from the property's type, patch casts to that setter as backward
    // does; every other value is written as it is.
    final var patchCasts = new HashMap<String, TypeMirror>();
    for (final var entry : backwardGives.entrySet()) {
      if (!transformedSources.contains(entry.getKey()) || !hasField(sourceFields, entry.getKey())) continue;
      final var property = fieldByName(sourceFields, entry.getKey()).type();
      final var slot = heldAs(writeSlotType(source, sourceRebuild, entry.getKey(), entry.getValue(), bridgePackage));
      final var parameter = chosenParameter(source, sourceRebuild, entry.getKey(), slot);
      final var assignable = processingEnv.getTypeUtils().isAssignable(heldAs(property), entry.getValue());
      final var setters = sourceRebuild.kind() == Rebuild.Kind.SETTERS;
      if (
        assignable &&
        ((setters && castsToChosen(entry.getValue(), parameter)) || reachesByCheck(entry.getValue(), parameter))
      ) {
        patchCasts.put(entry.getKey(), entry.getValue());
      }
    }
    patchSplits = new HashMap<>();
    patchSetters = sourceRebuild.kind() == Rebuild.Kind.SETTERS;
    patchOwner = source;
    final var patchInner = buildExpr(
      source,
      readPatch,
      sourceFields,
      writeStrategy,
      source,
      sourceRebuild,
      null,
      patchCasts.keySet(),
      patchCasts,
      Set.of()
    );
    patchSplits = Map.of();
    pathArg = guarded ? "__path" : null;
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
    if (carrierEl != null) {
      emitBridgeProvider(source, target, bridgeName, pkg);
      emitCarrierMarker(source, target, carrierEl, qualifiedBridge);
    }

    final var imports = new TreeSet<>(importsFor(fieldPlans));
    imports.add("io.github.eschizoid.telescope.Telescope");
    imports.add("io.github.eschizoid.telescope.conversion.BridgeFn");
    writeClass(
      qualifiedBridge,
      bridgeName,
      imports,
      "Generated by telescope-codegen for @Bridge " +
        source.getSimpleName() +
        (configsByPair.containsKey(thisPair) ? "." : " (auto-generated for nested sub-pair " + targetFq + ")."),
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
        // A constructor argument typed by the target's type variable is passed through this
        // identity, which hands javac the erasure the raw class's constructor takes.
        if (erasedArgumentUsed) {
          out.println("  private static <V> V __erased(final V value) {");
          out.println("    return value;");
          out.println("  }");
          out.println();
        }
        // A conversion result of a primitive's wrapper class written into that primitive, or one
        // it widens to, takes the primitive's default where it is null, read once.
        for (final var kind : orElseKinds) {
          final var primitive = processingEnv.getTypeUtils().getPrimitiveType(kind);
          final var wrapper = processingEnv.getTypeUtils().boxedClass(primitive).getQualifiedName();
          out.println(
            "  private static " +
              primitive +
              " __orElse(final " +
              wrapper +
              " value, final " +
              primitive +
              " fallback) {"
          );
          out.println("    return value == null ? fallback : value;");
          out.println("  }");
          out.println();
        }
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
        //
        // Each method reads every field into a local of the field's own type, so a field declared
        // as a generic class used raw makes each of them declare a raw local. The record already
        // says the type is raw; the methods only repeat it, so they carry the suppression the
        // record's own declaration needs rather than raising the warning again in a file nobody
        // wrote.
        final var rawFields = Stream.concat(sourceFields.stream(), targetFields.stream()).anyMatch(f ->
          mentionsRawUse(f.type())
        );
        final var namesRaw = rawNameSuppression(List.of(source, target));
        // A sealed interface, record or bean given type arguments converts through a sub-bridge
        // that names it raw, so the value it returns is held as the property's parameterized type
        // unchecked.
        final var bridgedArguments = Stream.concat(sourceFields.stream(), targetFields.stream()).anyMatch(f ->
          givesBridgedArguments(f.type())
        );
        // A member reached through a class the bridge names raw has its type erased, so a read or
        // a write through one whose erasure differs is unchecked.
        final var suppressRaw =
          paired.rawAccess() || bridgedArguments
            ? RAW_SUPPRESSION + "\n"
            : rawFields
              ? RAW_TYPES_SUPPRESSION + "\n"
              : namesRaw;
        // Null in -> null out, matching the runtime structural Iso. Also lets a null container
        // element (subBridge.forward(null) in the container helpers) pass through as null.
        final var pathAccess = guarded ? pathOverloadAccess(source, pkg) : null;
        emitDirection(out, suppressRaw, pathAccess, "forward", sourceFq, targetFq, "s", forwardBody);
        emitDirection(out, suppressRaw, pathAccess, "backward", targetFq, sourceFq, "t", backwardBody);
        // Sparse-overlay patch: read non-null fields of `partial` (a partially-populated target)
        // and apply them onto `base`. Mirrors the runtime Mapper#patch(base, partial) semantics.
        // Reference target components null-gate to base; primitive components autobox to non-null
        // and are always overlaid; nested components are written whole through the sub-bridge's
        // backward when the partial slot is non-null.
        out.print(suppressRaw);
        out.println(
          "  public static " + sourceFq + " patch(final " + sourceFq + " base, final " + targetFq + " partial) {"
        );
        // Patch returns a new object whenever base exists, matching the runtime Mapper#patch: a
        // null partial reads as one whose every slot is null, so the body rebuilds base.
        out.println("    if (base == null) return null;");
        emitMethodBody(out, patchBody);
        out.println("  }");
        out.println();
        emitBridgeHandles(
          out,
          namesRaw,
          bridgeName,
          sourceFq,
          targetFq,
          declares(thisPair),
          unwalkedSource,
          unwalkedTarget,
          guarded
        );
        emitContainerHelpers(out, fieldPlans, paired.source(), paired.target(), renames, pkg);
        emitNestedElements(out, nested, pkg);
      }
    );
  }

  /**
   * The settings of a bridge that decide how fields convert, by attribute, that {@code config}
   * gives: a bridge between two sealed interfaces converts no field itself, so none applies to it.
   */
  private static List<String> caseSettings(final BridgeConfig config) {
    final var given = new ArrayList<String>();
    if (!config.drops().isEmpty()) given.add("drops");
    if (!config.renames().isEmpty() || !config.renameFanouts().isEmpty()) given.add("renames");
    if (!config.transforms().isEmpty()) given.add("transforms");
    if (!config.constants().isEmpty()) given.add("constants");
    if (!config.computes().isEmpty()) given.add("computes");
    if (!config.defaults().isEmpty()) given.add("defaults");
    if (!config.viaMappers().isEmpty()) given.add("viaMappers");
    if (!"AUTO".equals(config.writeStrategy())) given.add("writeStrategy");
    return given;
  }

  // Sealed-source bridge: dispatch on the permits clause and delegate each case to the per-case
  // bridge that the user already declared with @Bridge on the subtype. The emitted forward/backward
  // dispatch through the Match.of(...).when(...).exhaustive() fluent over the sealed permits; no
  // field walking, no rebuild — just one dispatch arm per case. Requires every permit case to be
  // @Bridge-annotated and its target to be a permit of the sealed target.
  private void generateSealed(
    final TypeElement source,
    final TypeElement target,
    final Deque<TypePair> pending,
    final Set<TypePair> seen
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
    // A sealed pair with no @Bridge of its own is planned for the bridge whose
    // field holds it, so a refusal is reported there.
    final var ownPair = new TypePair(source.getQualifiedName().toString(), target.getQualifiedName().toString());
    final var heldBy = configsByPair.containsKey(ownPair) ? null : sealedSubPairParents.get(ownPair);
    final Element reportOn = heldBy == null ? source : heldBy;
    final var sealedName =
      heldBy == null
        ? "@Bridge sealed " + source.getSimpleName()
        : "sealed " + source.getSimpleName() + ", which @Bridge " + heldBy.getSimpleName() + " converts case by case,";
    final var ownConfig = configsByPair.get(ownPair);
    final var unapplied = ownConfig == null ? List.<String>of() : caseSettings(ownConfig);
    if (!unapplied.isEmpty()) {
      error(
        source,
        "@Bridge sealed " +
          source.getSimpleName() +
          " converts each case through the case's own @Bridge, which this bridge's " +
          String.join(", ", unapplied) +
          " would not reach. Declare them on the @Bridge of each case they apply to."
      );
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
            reportOn,
            "Subtype " + sourceCaseEl.getSimpleName() + " of " + sealedName + " must itself be @Bridge-annotated."
          );
        } else if (caseBridges.size() == 1) {
          // Single @Bridge whose target isn't in the sealed-target permits — name it explicitly.
          final var only = rawTargetValueFromMirror(caseBridges.getFirst());
          if (!(only instanceof DeclaredType decl)) continue;
          final var onlyEl = (TypeElement) decl.asElement();
          error(
            reportOn,
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
            reportOn,
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
      final var crossed = rules.caseArgumentsRefusal(
        source.asType(),
        target.asType(),
        sourceCaseEl.asType(),
        targetCaseEl.asType()
      );
      if (crossed != null) {
        error(reportOn, crossed);
        return;
      }
      final var caseBridgeFq = bridgeNameOf(sourceCaseEl, targetCaseEl).qualified();
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

    final var sourceFq = source.getQualifiedName().toString();
    final var targetFq = target.getQualifiedName().toString();
    final var bridge = bridgeNameOf(source, target);
    final var bridgeName = bridge.simple();
    final var qualifiedBridge = bridge.qualified();
    if (!claimBridgeName(bridge, source, target)) return;
    // A carrier-form umbrella lives beside its carrier like any carrier-form bridge, so it is
    // registered for the same ServiceLoader lookup.
    if (configsByPair.getOrDefault(new TypePair(sourceFq, targetFq), BridgeConfig.EMPTY).carrierFq() != null) {
      emitBridgeProvider(source, target, bridgeName, bridge.pkg());
    }

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
        // The members below name both roots and every case raw.
        final var named = new ArrayList<TypeElement>(List.of(source, target));
        for (final var e : entries) {
          named.add(e.sourceCase());
          named.add(e.targetCase());
        }
        final var namesRaw = rawNameSuppression(named);
        // Sealed-dispatch via Match — the dispatch is lattice-routed (Match composes internal
        // Prism instances per permit) and exhaustiveness is verified at class-load time by
        // .exhaustive() reading getPermittedSubclasses(). Belt-and-suspenders: if the processor
        // misses a permit, .exhaustive() throws loudly at class init rather than silently
        // defaulting to the unreachable-throw at first invocation of the orphan case.
        out.print(namesRaw);
        out.println("  private static final Function<" + sourceFq + ", " + targetFq + "> FORWARD =");
        out.println("    Match.<" + sourceFq + ", " + targetFq + ">of(" + sourceFq + ".class)");
        for (final var e : entries) {
          out.println("      .when(" + e.sourceCase().getQualifiedName() + ".class, " + e.bridgeFq() + "::forward)");
        }
        out.println("      .exhaustive();");
        out.println();
        out.print(namesRaw);
        out.println("  private static final Function<" + targetFq + ", " + sourceFq + "> BACKWARD =");
        out.println("    Match.<" + targetFq + ", " + sourceFq + ">of(" + targetFq + ".class)");
        for (final var e : entries) {
          out.println("      .when(" + e.targetCase().getQualifiedName() + ".class, " + e.bridgeFq() + "::backward)");
        }
        out.println("      .exhaustive();");
        out.println();
        out.print(namesRaw);
        out.println("  public static " + targetFq + " forward(final " + sourceFq + " s) {");
        // Null in -> null out, the same contract the field-sequence bridges emit. Match
        // dispatches
        // on the runtime class, which a null does not have, so without this the umbrella reports
        // an
        // unmatched case for a value every other generated bridge passes straight through.
        out.println("    if (s == null) return null;");
        out.println("    return FORWARD.apply(s);");
        out.println("  }");
        out.println();
        out.print(namesRaw);
        out.println("  public static " + sourceFq + " backward(final " + targetFq + " t) {");
        out.println("    if (t == null) return null;");
        out.println("    return BACKWARD.apply(t);");
        out.println("  }");
        out.println();
        // A sealed pair on a cycle passes the active path a parent hands it on to each case's own
        // bridge that takes one, so a reference that closes the cycle through a case converts to
        // null, as it does through any other bridge on the cycle.
        if (guardsCycles(source, target)) {
          final var access = pathOverloadAccess(source, bridge.pkg());
          for (final var direction : List.of("forward", "backward")) {
            final var forward = direction.equals("forward");
            final var inFq = forward ? sourceFq : targetFq;
            out.println(
              "  " +
                access +
                "static " +
                (forward ? targetFq : sourceFq) +
                " " +
                direction +
                "(final " +
                inFq +
                " v, final " +
                PATH_TYPE +
                " __path) {"
            );
            out.println("    if (v == null) return null;");
            for (final var e : entries) {
              final var inCase = (forward ? e.sourceCase() : e.targetCase()).getQualifiedName();
              final var path = guardsCycles(e.sourceCase(), e.targetCase()) ? ", __path" : "";
              out.println(
                "    if (v instanceof " + inCase + " c) return " + e.bridgeFq() + "." + direction + "(c" + path + ");"
              );
            }
            out.println("    return " + (forward ? "FORWARD" : "BACKWARD") + ".apply(v);");
            out.println("  }");
            out.println();
          }
        }
        // Sealed-umbrella patch: when base and partial land on matching cases, the per-case
        // bridge's patch overlays one onto the other.
        //
        // When they don't (base is a CreditCard, partial is a BankTransferEntity), the partial
        // has no defined overlay onto base: switching the concrete case through a full backward
        // conversion would turn "PATCH /payments/123 with a bank-transfer body" into a change of
        // payment type. The partial is ignored instead, and base is rebuilt through its own
        // case's patch with a null partial, so the result is still a new object. A caller who
        // wants the case switch calls backward() explicitly. The switch over base is exhaustive
        // over the sealed permits, which javac checks.
        out.print(namesRaw);
        out.println(
          "  public static " + sourceFq + " patch(final " + sourceFq + " base, final " + targetFq + " partial) {"
        );
        out.println("    if (base == null) return null;");
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
        out.println("    return switch (base) {");
        for (final var e : entries) {
          final var sCase = e.sourceCase().getQualifiedName();
          final var tCase = e.targetCase().getQualifiedName();
          out.println("      case " + sCase + " sb -> " + e.bridgeFq() + ".patch(sb, (" + tCase + ") null);");
        }
        out.println("    };");
        out.println("  }");
        out.println();
        emitBridgeHandles(out, namesRaw, bridgeName, sourceFq, targetFq, declares(ownPair), Set.of(), Set.of());
      }
    );
  }

  /**
   * The {@code Fn} class, {@code BRIDGE_FN} and {@code BRIDGE}, each typed over the two classes as
   * {@code forward} and {@code backward} name them. {@code suppression} is the line each carries,
   * or empty. {@code declared} says a {@code @Bridge} names the pair. The two sets are the
   * properties of each side the bridge does not walk.
   */
  private static void emitBridgeHandles(
    final PrintWriter out,
    final String suppression,
    final String bridgeName,
    final String sourceFq,
    final String targetFq,
    final boolean declared,
    final Set<String> unwalkedSource,
    final Set<String> unwalkedTarget
  ) {
    emitBridgeHandles(
      out,
      suppression,
      bridgeName,
      sourceFq,
      targetFq,
      declared,
      unwalkedSource,
      unwalkedTarget,
      false
    );
  }

  /**
   * As above; {@code takesPath} says the bridge has the path-carrying overloads {@link
   * #emitDirection} writes for a pair that can reach itself, which the {@code Fn} then exposes, so
   * a runtime mapper converting the pair on a cycle hands the bridge its own path.
   */
  private static void emitBridgeHandles(
    final PrintWriter out,
    final String suppression,
    final String bridgeName,
    final String sourceFq,
    final String targetFq,
    final boolean declared,
    final Set<String> unwalkedSource,
    final Set<String> unwalkedTarget,
    final boolean takesPath
  ) {
    out.println("  /** One concrete BridgeFn type per @Bridge — monomorphic dispatch site. */");
    out.print(suppression);
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
    out.println();
    out.println("    @Override");
    out.println("    public " + sourceFq + " patch(final " + sourceFq + " base, final " + targetFq + " partial) {");
    out.println("      return " + bridgeName + ".patch(base, partial);");
    out.println("    }");
    out.println();
    if (takesPath) {
      out.println("    @Override");
      out.println("    public boolean takesActivePath() {");
      out.println("      return true;");
      out.println("    }");
      out.println();
      out.println("    @Override");
      out.println("    public " + targetFq + " forward(final " + sourceFq + " s, final " + PATH_TYPE + " __path) {");
      out.println("      return " + bridgeName + ".forward(s, __path);");
      out.println("    }");
      out.println();
      out.println("    @Override");
      out.println("    public " + sourceFq + " backward(final " + targetFq + " t, final " + PATH_TYPE + " __path) {");
      out.println("      return " + bridgeName + ".backward(t, __path);");
      out.println("    }");
      out.println();
    }
    if (declared) {
      out.println("    @Override");
      out.println("    public boolean declaresPair() {");
      out.println("      return true;");
      out.println("    }");
      out.println();
    }
    out.println("    @Override");
    out.println("    public boolean declaresUnwalkedProperties() {");
    out.println("      return true;");
    out.println("    }");
    emitPropertySet(out, "unwalkedSourceProperties", unwalkedSource);
    emitPropertySet(out, "unwalkedTargetProperties", unwalkedTarget);
    out.println("  }");
    out.println();
    emitBridgeFnJavadoc(out);
    out.print(suppression);
    out.println("  public static final BridgeFn<" + sourceFq + ", " + targetFq + "> BRIDGE_FN = new Fn();");
    out.print(suppression);
    out.println(
      "  public static final Telescope<" + sourceFq + ", " + targetFq + "> BRIDGE = Telescope.bridge(BRIDGE_FN);"
    );
  }

  /**
   * An override of the {@code BridgeFn} method {@code name} answering {@code properties}, if any.
   */
  private static void emitPropertySet(final PrintWriter out, final String name, final Set<String> properties) {
    if (properties.isEmpty()) return;
    final var listed = properties
      .stream()
      .map(p -> "\"" + p + "\"")
      .collect(Collectors.joining(", "));
    out.println();
    out.println("    @Override");
    out.println("    public java.util.Set<String> " + name + "() {");
    out.println("      return java.util.Set.of(" + listed + ");");
    out.println("    }");
  }

  /**
   * The {@code rawtypes} suppression line for a member of a bridge that names any of {@code
   * classes}, or empty when none is generic.
   *
   * <p>A bridge names each class raw. A static member cannot name a class's type variables, and a
   * wildcard is no substitute in a public signature: {@code BridgeFn<Src<?>, Dst<?>>} is not
   * assignable to the {@code BridgeFn<Src, Dst>} a caller declares, and a {@code Src<?>} returned
   * by {@code backward} cannot be assigned to the {@code Src<String>} a caller holds. The raw type
   * accepts both, so a raw name is the type every caller can use.
   */
  private static String rawNameSuppression(final List<TypeElement> classes) {
    for (final var c : classes) if (!c.getTypeParameters().isEmpty()) return RAW_TYPES_SUPPRESSION + "\n";
    return "";
  }

  /**
   * Block form of {@code body} preceded by one {@code final} declaration per hoisted read. {@code
   * body} arrives in {@link #buildExpr}'s two shapes: an expression, which gets wrapped in a block
   * returning it, and an existing block (the setters write strategy emits one), whose brace the
   * declarations are spliced behind — wrapping a block would emit {@code return { … };}.
   */
  private static String withPrelude(final LinkedHashMap<String, String> locals, final String body) {
    if (locals.isEmpty()) return body;
    final var sb = new StringBuilder("{ ");
    for (final var decl : locals.values()) sb.append("final ").append(decl).append(" ");
    if (body.startsWith("{")) return sb.append(body.substring(1).trim()).toString();
    return sb.append("return ").append(body).append("; }").toString();
  }

  /**
   * The javadoc above the emitted {@code BRIDGE_FN} constant. Both the plain and the sealed bridge
   * emit it, and it describes what the constant is rather than what it costs: which of the two
   * shapes measures faster is tier-dependent and has not been stable across benchmark runs, so a
   * figure quoted here would be a claim this code cannot keep true. The published comparison is the
   * one place the numbers live.
   */
  private static void emitBridgeFnJavadoc(final PrintWriter out) {
    out.println("  /** Directly-callable form of this bridge: a {@link BridgeFn} over the generated");
    out.println("   * static methods, reached through one interface hop and without lattice composition.");
    out.println("   * {@code BRIDGE} is the composable value for the same conversion — pick by the shape");
    out.println("   * the call site needs. */");
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
    // LIST/SET/MAP_VALUES only: the fully-qualified name of the concrete collection/map class to
    // allocate for
    // the forward (target) and backward (source) outputs, used by the inline identity-element
    // copy
    // in applyForward/applyBackward. Null for non-container kinds (the element-bridging helper
    // path
    // recomputes its allocation from the field types directly). Mirrors DeepMap's allocation
    // table.
    String fwdContainerImpl,
    String bwdContainerImpl,
    // LIST/SET/MAP_VALUES only: true when at least one side is allocated as a Collection/Map type
    // the adopter wrote rather than a JDK class — a raw subtype (`class ImageUrls extends
    // ArrayList<ImageUrl>`, whose element type lives in the supertype) or a generic one (`class
    // MyList<T> extends ArrayList<T>`). Either is allocated via its no-arg constructor, because
    // subclasses do not inherit the JDK copy ctor, so both route to the self-contained helpers
    // instead of the inline copy-ctor path.
    boolean rawContainer,
    // RECURSE only: true when subBridgeName is a user-supplied @ViaMapper class rather than an
    // auto-derived sub-bridge. Auto-derived bridges open their forward/backward with a null
    // guard; a user class carries no such guarantee, so the caller emits the guard.
    boolean userSuppliedBridge
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
      // A container of one declared type on both sides, copied by the runtime's ContainerCopy.of
      // so both paths copy the same way. subBridgeName carries the declared raw type's name.
      COPY,
      // Two different enums converted constant to constant by name. subBridgeName names the
      // nested class whose forward and backward hold the two switches.
      ENUM,
    }

    static FieldPlan identity() {
      return new FieldPlan(Kind.IDENTITY, null, null, null, null, null, null, false, false);
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
        false,
        userSuppliedBridge
      );
    }

    FieldPlan asRawContainer() {
      return new FieldPlan(
        kind,
        subBridgeName,
        qualifierMethod,
        fwdNullDefault,
        bwdNullDefault,
        fwdContainerImpl,
        bwdContainerImpl,
        true,
        userSuppliedBridge
      );
    }

    // Primitive ↔ boxed wrapper. The direction that writes the primitive side null-coalesces a null
    // read to that primitive's JLS default; the direction that writes the wrapper passes through
    // (null is a legal wrapper value). Exactly one of the two defaults is non-null for any given
    // pair. Matches the runtime DeepMap.primitiveWrapperIso.
    static FieldPlan primWrapper(final String fwdNullDefault, final String bwdNullDefault) {
      return new FieldPlan(Kind.PRIM_WRAPPER, null, null, fwdNullDefault, bwdNullDefault, null, null, false, false);
    }

    static FieldPlan recurse(final String subBridgeName) {
      return new FieldPlan(
        Kind.RECURSE,
        Objects.requireNonNull(subBridgeName),
        null,
        null,
        null,
        null,
        null,
        false,
        false
      );
    }

    // A RECURSE plan whose bridge is the user's @ViaMapper class rather than an auto-derived
    // sub-bridge — the caller must null-guard the conversion call.
    static FieldPlan viaBridge(final String subBridgeName) {
      return new FieldPlan(
        Kind.RECURSE,
        Objects.requireNonNull(subBridgeName),
        null,
        null,
        null,
        null,
        null,
        false,
        true
      );
    }

    static FieldPlan copy(final String declaredRawFqn) {
      return new FieldPlan(
        Kind.COPY,
        Objects.requireNonNull(declaredRawFqn),
        null,
        null,
        null,
        null,
        null,
        false,
        false
      );
    }

    static FieldPlan ofKind(final Kind kind, final String subBridgeName) {
      return new FieldPlan(kind, Objects.requireNonNull(subBridgeName), null, null, null, null, null, false, false);
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
        false,
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

  /**
   * The container shape of a type, asked of the shared pairing spec so that what counts as a
   * container is one decision rather than two. That includes a class declaring no type parameters
   * of its own, which the spec views through the supertype that fixes them. {@code null} when the
   * spec gives no view, as for a generic class used raw or a map whose key type the spec cannot
   * compare, and nothing else is asked: a container the spec gives no view is not lifted here.
   *
   * <p>Both sides are needed because a field declared as the general {@code Collection} has named
   * no shape of its own, and the spec settles such a view against the other side of the pair.
   */
  private ContainerShape containerShapeOf(final TypeMirror type, final TypeMirror other) {
    final var view = rules.settledAgainst(rules.containerViewOf(type), rules.containerViewOf(other));
    if (view == null) return null;
    return new ContainerShape(
      switch (view.kind()) {
        case LIST -> FieldPlan.Kind.LIST;
        case SET -> FieldPlan.Kind.SET;
        case MAP_VALUES -> FieldPlan.Kind.MAP_VALUES;
        case OPTIONAL -> FieldPlan.Kind.OPTIONAL;
        // Settled against the other side above, so a view still carrying it here means the spec
        // stopped doing that.
        case COLLECTION -> throw new IllegalStateException(
          "a Collection-declared container reached the plan without taking a shape"
        );
      },
      view.elementType(),
      view.keyType()
    );
  }

  /**
   * The suppression a helper carries when a container it names is, or holds, a generic class used
   * raw: such a type is raw wherever it is written, and a copy between raw containers is unchecked
   * by construction.
   */
  private static final String RAW_SUPPRESSION = "  @SuppressWarnings({\"unchecked\", \"rawtypes\"})";

  /**
   * The suppression a member carries when it names a generic class raw and reads nothing through it
   * unchecked.
   */
  private static final String RAW_TYPES_SUPPRESSION = "  @SuppressWarnings(\"rawtypes\")";

  /**
   * Whether a type is, or has among its type arguments at any depth, a sealed interface or a record
   * or bean given type arguments, which converts through a sub-bridge that names it raw.
   */
  private boolean givesBridgedArguments(final TypeMirror type) {
    if (!(type instanceof DeclaredType declared)) return false;
    final var bridged = sealedInterface(declared) || isReflectableDeclared(declared);
    if (bridged && !declared.getTypeArguments().isEmpty()) return true;
    return declared.getTypeArguments().stream().anyMatch(this::givesBridgedArguments);
  }

  /** Whether a type is, or has among its type arguments at any depth, a generic class used raw. */
  private static boolean mentionsRawUse(final TypeMirror type) {
    if (usedRaw(type)) return true;
    return (
      type instanceof DeclaredType declared &&
      declared.getTypeArguments().stream().anyMatch(BridgeProcessor::mentionsRawUse)
    );
  }

  /** Whether a declared type is a generic class written without its type arguments. */
  private static boolean usedRaw(final TypeMirror type) {
    return (
      type instanceof DeclaredType declared &&
      declared.getTypeArguments().isEmpty() &&
      !((TypeElement) declared.asElement()).getTypeParameters().isEmpty()
    );
  }

  /**
   * The kind a pair is copied as when the shared spec decides an element-for-element copy, or null
   * when it decides anything else. The spec has already checked that the two sides agree on every
   * kind axis, so the target alone says which kind that is.
   */
  private FieldPlan.Kind copyKindOf(final PairDecision<TypeMirror> decision, final TypeMirror target) {
    if (decision instanceof PairDecision.MapCopy) return FieldPlan.Kind.MAP_VALUES;
    if (!(decision instanceof PairDecision.CollectionCopy)) return null;
    return assignableToRaw(target, "java.util.Set") ? FieldPlan.Kind.SET : FieldPlan.Kind.LIST;
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

  /**
   * Why a container field cannot be emitted, in the words the shared allocation rules refuse it
   * with, which are the words the reflective path refuses the same field with.
   */
  private static String refusedContainerMessage(
    final TypeElement source,
    final TypeElement target,
    final String fieldName,
    final String reason
  ) {
    return (
      "@Bridge " + source.getSimpleName() + " -> " + target.getSimpleName() + ": field '" + fieldName + "': " + reason
    );
  }

  /**
   * Reports a sorted target nothing can order, as the shared rules decide it, and answers whether
   * it did: a set whose converted elements are of a class that is not comparable, or a map whose
   * keys are of one and whose class cannot be handed a comparator. Asked once the element pair has
   * planned, so a pairing refused for another reason keeps that reason.
   */
  private boolean refusesUnorderable(
    final TypeElement source,
    final TypeElement target,
    final Field sf,
    final Field tf,
    final ContainerShape srcShape,
    final ContainerShape tgtShape
  ) {
    final String message;
    if (
      srcShape.kind() == FieldPlan.Kind.SET &&
      rules.unorderableSortedTarget(srcShape.elementType(), tgtShape.elementType(), tf.type())
    ) {
      message = PairingMessages.unorderableSortedElement(
        sf.name(),
        tf.type().toString(),
        tgtShape.elementType().toString()
      );
    } else if (
      srcShape.kind() == FieldPlan.Kind.MAP_VALUES && rules.unorderableSortedKeys(tgtShape.keyType(), tf.type())
    ) {
      message = PairingMessages.unorderableSortedKey(sf.name(), tf.type().toString(), tgtShape.keyType().toString());
    } else {
      return false;
    }
    error(source, "@Bridge " + source.getSimpleName() + " -> " + target.getSimpleName() + ": " + message);
    return true;
  }

  /**
   * Whether this side may be built by handing {@code from}, the other side's value, to a copy
   * constructor, as the shared allocation rules decide it for the reflective path too. The test is
   * directional: a constructor that takes a {@code Collection} accepts any of them, while one
   * narrowed to {@code ArrayList} does not accept a {@code List}, and only the value actually being
   * passed decides which. A side reached through its builder is never copied: the builder is what
   * builds it.
   */
  private boolean copyConstructs(
    final TypeMirror container,
    final FieldPlan.Kind kind,
    final TypeMirror from,
    final String bridgePkg
  ) {
    if (builderAllocExpr(container, kind, bridgePkg) != null) return false;
    return allocation.copiesInPlace(container, family(kind), from, bridgePkg);
  }

  private static boolean isContainerKind(final FieldPlan.Kind kind) {
    return kind == FieldPlan.Kind.LIST || kind == FieldPlan.Kind.SET || kind == FieldPlan.Kind.MAP_VALUES;
  }

  /**
   * The class a container declared as {@code container}, built as {@code kind}, is rebuilt as, by
   * the shared allocation rules the reflective path builds the same field by: the class the shared
   * table names, the family default standing in for an interface or abstract type, or the declared
   * class itself. A declaration the rules refuse answers its own name, which every gate refuses
   * before anything is rendered from it.
   */
  private String concreteImplFqn(final TypeMirror container, final FieldPlan.Kind kind) {
    return allocation.implementationFor(container, family(kind)) instanceof Allocation.Build build
      ? build.implName()
      : ((TypeElement) ((DeclaredType) container).asElement()).getQualifiedName().toString();
  }

  /**
   * The shared rules' answer for a container the bridge, emitted into {@code bridgePkg}, builds.
   */
  private Allocation allocationOf(final TypeMirror container, final FieldPlan.Kind kind, final String bridgePkg) {
    return allocation.allocate(container, family(kind), bridgePkg);
  }

  /** The family a container plan builds, as the shared rules name it. */
  private static ContainerView.Kind family(final FieldPlan.Kind kind) {
    return switch (kind) {
      case LIST -> ContainerView.Kind.LIST;
      case SET -> ContainerView.Kind.SET;
      case MAP_VALUES -> ContainerView.Kind.MAP_VALUES;
      default -> throw new IllegalStateException("not a collection/map kind: " + kind);
    };
  }

  private static String simpleName(final String fqn) {
    final var dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  /**
   * Allocation expression for a container that will be filled with {@code src.size()} elements,
   * written through the call the shared allocation rules decide, which is the call the reflective
   * path makes for the same class.
   *
   * <p>An ordering to carry decides first, whatever the call: a sorted container is built from the
   * order its source kept rather than from a size. A comparator that is null is natural ordering,
   * which the no-argument constructor already gives; handing the null over instead would reach a
   * constructor free to reject it.
   *
   * @param typeArgs the emitted type-argument clause, angle brackets included, or empty for a class
   *     that declares no type parameters or is allocated raw; see {@link #typeArgumentClause}
   * @param ordering the constructor argument carrying a sorted container's ordering, empty for
   *     every class that has none to carry
   */
  private String sizedAlloc(
    final TypeMirror container,
    final FieldPlan.Kind kind,
    final String implFqn,
    final String typeArgs,
    final String ordering
  ) {
    if (!ordering.isEmpty()) {
      return (
        ordering + " == null ? new " + implFqn + typeArgs + "() : new " + implFqn + typeArgs + "(" + ordering + ")"
      );
    }
    final var call =
      allocation.implementationFor(container, family(kind)) instanceof Allocation.Build build
        ? build.call()
        : Allocation.Call.NO_ARG;
    return switch (call) {
      // A factory method takes explicit type arguments or none: a diamond is not written there, and
      // leaving them off lets the assignment infer them, as the diamond does for a constructor.
      case TABLE_FACTORY -> implFqn +
      "." +
      (typeArgs.equals("<>") ? "" : typeArgs) +
      "new" +
      simpleName(implFqn) +
      "(src.size())";
      case COUNT -> "new " + implFqn + typeArgs + "(src.size())";
      case TABLE_ARITHMETIC -> "new " + implFqn + typeArgs + "((int) Math.ceil(src.size() / 0.75d))";
      case KEY_CLASS -> "new " + implFqn + typeArgs + "(" + keyClassLiteral(container) + ")";
      case NO_ARG, ORDERING -> "new " + implFqn + typeArgs + "()";
    };
  }

  /**
   * The class literal of a declared map's key type, which a container built from one is handed. The
   * shared rules build such a container only where the declaration names a key type, and every gate
   * asks them before anything is rendered, so a missing one is a broken invariant, reported as the
   * refusal those rules would have made rather than as a null dereference.
   */
  private String keyClassLiteral(final TypeMirror container) {
    final var view = rules.containerViewOf(container);
    if (view == null || view.keyType() == null) {
      throw new IllegalStateException(PairingMessages.noKeyClass(container.toString()));
    }
    return processingEnv.getTypeUtils().erasure(view.keyType()) + ".class";
  }

  /**
   * What follows the class name when {@code implFqn} is allocated: {@code typeArgs} in angle
   * brackets, which is the diamond when it is empty, or nothing at all for a class that declares no
   * type parameters. javac rejects a diamond and an argument list alike on a non-generic class, and
   * a container subtype that fixes its supertype's arguments is one.
   */
  private String typeArgumentClause(final String implFqn, final String typeArgs) {
    final var implEl = processingEnv.getElementUtils().getTypeElement(implFqn);
    return implEl != null && implEl.getTypeParameters().isEmpty() ? "" : "<" + typeArgs + ">";
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
    final Map<String, ViaLift> viaLifts,
    final Deque<TypePair> pending,
    final Set<TypePair> seen,
    final boolean lenient,
    final String parentPkg
  ) {
    nestedElements.clear();
    viaElementMethods.clear();
    threadedRefs.clear();
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
      // A @ViaMapper lifted over a container's elements is planned as any other container whose
      // elements convert, with its own element class in place of a sub-bridge.
      final var lift = viaLifts.get(sf.name());
      if (viaMappers.containsKey(sf.name()) && lift == null) {
        plans.put(sf.name(), FieldPlan.viaBridge(viaMappers.get(sf.name())));
        continue;
      }
      final var tf = fieldByName(targetFields, renames.getOrDefault(sf.name(), sf.name()));
      // (1) Same type → identity, unless the shared rules copy it: a container of one declared
      //     type is copied by the runtime's ContainerCopy, the copy the reflective path makes, so
      //     the target never shares the source's container and its elements pass through.
      if (lift == null && isSameType(sf.type(), tf.type())) {
        plans.put(
          sf.name(),
          passesThrough(sf.type(), sf.name()) ? FieldPlan.identity() : FieldPlan.copy(rawName(sf.type()))
        );
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
      // (1c) Two different enums: constant to constant by name, where the shared spec finds a
      //      counterpart for every constant in each direction the bridge converts.
      if (rules.bothEnums(sf.type(), tf.type())) {
        final var name = planEnum(source, target, sf.name(), sf.type(), tf.type(), lenient);
        if (name == null) return null;
        plans.put(sf.name(), FieldPlan.ofKind(FieldPlan.Kind.ENUM, name));
        continue;
      }
      // (2) Container shape detection — both sides container of the same kind with element types
      //     that need their own sub-bridge. List/Set/Optional/Map values, key-equal Map.
      final var srcShape = containerShapeOf(sf.type(), tf.type());
      final var tgtShape = containerShapeOf(tf.type(), sf.type());
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
        final var subPlan =
          lift != null
            ? viaElement(srcShape, tgtShape, lift, viaMappers.get(sf.name()))
            : planElementSubBridge(
                source,
                target,
                sf.name(),
                srcShape.elementType(),
                tgtShape.elementType(),
                srcShape.kind(),
                pending,
                seen,
                lenient,
                parentPkg
              );
        if (subPlan == null) return null;
        if (refusesUnorderable(source, target, sf, tf, srcShape, tgtShape)) return null;
        // Only one of the container routes copy-constructs. The inline copy hands the source
        // container to the output's constructor and is reachable solely when the element type is
        // identity; every other route — the self-contained helper for identity elements, the
        // element-bridging helpers for the rest — allocates no-arg and fills. So the constructor a
        // container must offer depends on the route it takes, and the route depends on its
        // elements. Forward hands the source to the target's constructor and backward does the
        // reverse, so each side is asked about the value it will actually receive.
        if (isContainerKind(subPlan.kind())) {
          // Whatever route the container takes, it allocates a class that has to BE the declared
          // type. That is decided before the route is, so it is asked first.
          final var unassignable = firstUnassignableContainer(sf.type(), tf.type(), subPlan.kind(), parentPkg);
          if (unassignable != null) {
            error(source, refusedContainerMessage(source, target, sf.name(), unassignable));
            return null;
          }
          final var elementIdentity = IDENTITY_ELEMENT_SENTINEL.equals(subPlan.subBridgeName());
          // Which side a copy constructor can build is the shared allocation rules' decision, which
          // the reflective path makes too. A side reached through its builder is not copied, since
          // the builder is what builds it.
          final var inlineCopy =
            elementIdentity &&
            copyConstructs(tf.type(), subPlan.kind(), sf.type(), parentPkg) &&
            copyConstructs(sf.type(), subPlan.kind(), tf.type(), parentPkg);
          if (!inlineCopy) {
            final var badAlloc = firstNonAllocatableContainer(sf.type(), tf.type(), subPlan.kind(), parentPkg);
            if (badAlloc != null) {
              error(source, refusedContainerMessage(source, target, sf.name(), badAlloc));
              return null;
            }
            // Identity elements have no helper of their own, so they take the self-contained one.
            // Bridged elements already route to emitListHelper / emitSetHelper / emitMapHelper,
            // which allocate the same way — they only needed the allocation checked first.
            if (elementIdentity) {
              plans.put(sf.name(), subPlan.asRawContainer());
              continue;
            }
          }
        }
        // Attach the concrete-impl class the inline identity-element copy allocates: the target's
        // class on forward, the source's on backward — so a field typed as a concrete subtype
        // (LinkedList, TreeSet, …) is rebuilt as that class, not the default impl.
        final var withImpls = switch (subPlan.kind()) {
          case LIST, SET, MAP_VALUES -> subPlan.withContainerImpls(
            concreteImplFqn(tf.type(), subPlan.kind()),
            concreteImplFqn(sf.type(), subPlan.kind())
          );
          default -> subPlan;
        };
        plans.put(sf.name(), withImpls);
        continue;
      }
      // (2-copy) A same-kind pair the shared spec copies element for element with no view to lift
      //     through: a generic class used raw against another used raw, or against a side fixing
      //     every argument to Object. Nothing either side holds can be of a type the other does not
      //     admit, so the elements are copied into the class the target allocates.
      final var copyRefusal = rules.copyRefusal(sf.type(), tf.type());
      if (
        copyRefusal != null && rules.decidePair(sf.type(), tf.type(), sf.name()) instanceof PairDecision.Incompatible
      ) {
        // A pair of same-kind containers the shared spec refuses because one side has no class to
        // build, refused in the spec's words, which are the reflective path's.
        error(source, refusedContainerMessage(source, target, sf.name(), copyRefusal));
        return null;
      }
      final var copy = copyKindOf(rules.decidePair(sf.type(), tf.type(), sf.name()), tf.type());
      if (copy != null) {
        // The spec copies an interface only where the allocation table names a default for it, and
        // every default implements the declaration it is named for, so assignability holds by
        // construction. A concrete class it cannot probe, and its constructor is checked here.
        final var badAlloc = firstNonAllocatableContainer(sf.type(), tf.type(), copy, parentPkg);
        if (badAlloc != null) {
          error(source, refusedContainerMessage(source, target, sf.name(), badAlloc));
          return null;
        }
        plans.put(sf.name(), FieldPlan.ofKind(copy, IDENTITY_ELEMENT_SENTINEL).asRawContainer());
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
          lenient,
          parentPkg
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
          lenient,
          parentPkg
        );
        if (subPlan == null) return null;
        plans.put(sf.name(), subPlan);
        continue;
      }
      // (4) Both sides are scalar reflectable declared types, or two sealed interfaces whose
      //     bridge dispatches on the case → sub-bridge. A pair given type arguments the shared
      //     rules refuse is refused in the rules' words, since its sub-bridge names both raw.
      final var argumentsRefusal = parameterizedRefusal(sf.type(), tf.type(), sf.name());
      if (argumentsRefusal != null) {
        error(source, "@Bridge " + source.getSimpleName() + " -> " + target.getSimpleName() + ": " + argumentsRefusal);
        return null;
      }
      if (
        sf.type() instanceof DeclaredType st &&
        tf.type() instanceof DeclaredType tt &&
        ((isReflectableDeclared(st) && isReflectableDeclared(tt)) || dispatchesCases(st, tt, sf.name()))
      ) {
        final var subSourceEl = (TypeElement) st.asElement();
        final var subTargetEl = (TypeElement) tt.asElement();
        final var subPair = new TypePair(
          subSourceEl.getQualifiedName().toString(),
          subTargetEl.getQualifiedName().toString()
        );
        final var sealedSub = dispatchesCases(st, tt, sf.name());
        if (sealedSub) sealedSubPairParents.putIfAbsent(subPair, source);
        final var abstractSub = sealedSub ? null : firstAbstractSide(subSourceEl, subTargetEl);
        if (abstractSub != null) {
          error(
            source,
            "@Bridge field \"" +
              sf.name() +
              "\" needs a sub-bridge for " +
              abstractSub.getSimpleName() +
              ", which is abstract and has no constructor to rebuild through. Name a concrete" +
              " type for that field, or give it an explicit @ViaMapper."
          );
          return null;
        }
        if (!lenient) recordStrictReference(subPair, source, target, sf.type(), tf.type(), sf.name());
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
        final var subBridgeName = subBridgeReference(subSourceEl, subTargetEl, parentPkg);
        threadPathTo(source, target, subSourceEl, subTargetEl, subBridgeName);
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
          ") and no auto-bridge could be derived. Both sides must be records/classes" +
          " telescope can introspect (or the same type), or matching containers thereof."
      );
      return null;
    }
    return plans;
  }

  /**
   * The element plan for a {@code @ViaMapper} lifted over a container: a nested element class whose
   * {@code forward} and {@code backward} call the lifted methods on each element that is not null,
   * as a lifted element mapper does on the reflective path.
   */
  private FieldPlan viaElement(
    final ContainerShape srcShape,
    final ContainerShape tgtShape,
    final ViaLift lift,
    final String mapper
  ) {
    final var name = "__Nested" + nestedElements.size();
    nestedElements.put(
      name,
      new NestedElement(srcShape.elementType(), tgtShape.elementType(), FieldPlan.viaBridge(mapper))
    );
    viaElementMethods.put(name, lift);
    return FieldPlan.ofKind(srcShape.kind(), name);
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
    final boolean lenient,
    final String parentPkg
  ) {
    if (isSameType(srcElement, tgtElement)) {
      // Container kind matters (lift), but no sub-bridge — the element passes through. Use a
      // sentinel "IDENTITY" sub-bridge name; the emit code recognises it and skips the sub-call.
      if (passesThrough(srcElement, fieldName)) return FieldPlan.ofKind(kind, IDENTITY_ELEMENT_SENTINEL);
      // An element that is itself a container the shared rules copy gets a nested element class
      // whose forward and backward make the runtime's copy of it.
      final var name = "__Nested" + nestedElements.size();
      nestedElements.put(name, new NestedElement(srcElement, tgtElement, FieldPlan.copy(rawName(srcElement))));
      return FieldPlan.ofKind(kind, name);
    }
    if (rules.bothEnums(srcElement, tgtElement)) {
      final var name = planEnum(parentSource, parentTarget, fieldName, srcElement, tgtElement, lenient);
      return name == null ? null : FieldPlan.ofKind(kind, name);
    }
    final var innerSrc = containerShapeOf(srcElement, tgtElement);
    final var innerTgt = containerShapeOf(tgtElement, srcElement);
    if (
      innerSrc != null &&
      innerTgt != null &&
      innerSrc.kind() == innerTgt.kind() &&
      innerSrc.kind() != FieldPlan.Kind.OPTIONAL
    ) {
      return planNestedElement(
        parentSource,
        parentTarget,
        fieldName,
        srcElement,
        tgtElement,
        innerSrc,
        innerTgt,
        kind,
        pending,
        seen,
        lenient,
        parentPkg
      );
    }
    // An element pair the shared spec copies element for element, such as a generic class used
    // raw against another, is a nested container with no view to lift through: it gets a nested
    // element class whose helpers copy, as the same pair does when it is a field.
    final var copy = copyKindOf(rules.decidePair(srcElement, tgtElement, fieldName), tgtElement);
    if (copy != null) {
      // Assignable by construction, as for a field; only a concrete class's constructor is open,
      // and whether the nested element class can write each container's name.
      final var unnameable = firstUnnameableContainer(srcElement, tgtElement, parentPkg);
      if (unnameable != null) {
        error(parentSource, unnameableContainerMessage(parentSource, parentTarget, fieldName, unnameable, parentPkg));
        return null;
      }
      final var badAlloc = firstNonAllocatableContainer(srcElement, tgtElement, copy, parentPkg);
      if (badAlloc != null) {
        error(parentSource, refusedContainerMessage(parentSource, parentTarget, fieldName, badAlloc));
        return null;
      }
      final var name = "__Nested" + nestedElements.size();
      nestedElements.put(
        name,
        new NestedElement(srcElement, tgtElement, FieldPlan.ofKind(copy, IDENTITY_ELEMENT_SENTINEL).asRawContainer())
      );
      return FieldPlan.ofKind(kind, name);
    }
    final var argumentsRefusal = parameterizedRefusal(srcElement, tgtElement, fieldName);
    if (argumentsRefusal != null) {
      error(
        parentSource,
        "@Bridge " + parentSource.getSimpleName() + " -> " + parentTarget.getSimpleName() + ": " + argumentsRefusal
      );
      return null;
    }
    if (
      srcElement instanceof DeclaredType sd &&
      tgtElement instanceof DeclaredType td &&
      ((isReflectableDeclared(sd) && isReflectableDeclared(td)) || dispatchesCases(sd, td, fieldName))
    ) {
      final var subSourceEl = (TypeElement) sd.asElement();
      final var subTargetEl = (TypeElement) td.asElement();
      final var subPair = new TypePair(
        subSourceEl.getQualifiedName().toString(),
        subTargetEl.getQualifiedName().toString()
      );
      if (dispatchesCases(sd, td, fieldName)) sealedSubPairParents.putIfAbsent(subPair, parentSource);
      if (!lenient) recordStrictReference(subPair, parentSource, parentTarget, srcElement, tgtElement, fieldName);
      // Container-element sub-pair: same Lombok deferral as the field sub-bridge path above.
      if (seen.add(subPair)) {
        if (shouldDeferSubPair(subSourceEl, subTargetEl)) deferredPairs.add(subPair);
        else pending.add(subPair);
      }
      // Leniency propagates into the element pair too, matching the scalar sub-pair path.
      if (lenient) lenientPairs.add(subPair);
      final var subBridgeName = subBridgeReference(subSourceEl, subTargetEl, parentPkg);
      threadPathTo(parentSource, parentTarget, subSourceEl, subTargetEl, subBridgeName);
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
        "). Element types must match exactly or both be records/classes telescope can" +
        " introspect."
    );
    return null;
  }

  /** A strict bridge's reference to an auto-derived sub-pair, by field. */
  private record StrictReference(
    TypeElement source,
    TypeElement target,
    TypeMirror srcType,
    TypeMirror tgtType,
    String fieldName
  ) {}

  /** The strict references to each sub-pair, collected across rounds. */
  private final Map<TypePair, List<StrictReference>> strictReferences = new HashMap<>();

  private void recordStrictReference(
    final TypePair subPair,
    final TypeElement source,
    final TypeElement target,
    final TypeMirror srcType,
    final TypeMirror tgtType,
    final String fieldName
  ) {
    strictReferences
      .computeIfAbsent(subPair, __ -> new ArrayList<>())
      .add(new StrictReference(source, target, srcType, tgtType, fieldName));
  }

  /**
   * Refuse each strict bridge that reaches, through an auto-derived sub-pair, an enum pair the
   * sub-bridge converts forward only. A sub-bridge is emitted once per pair, lenient when any
   * parent reaching it is, so a strict parent of a sub-pair a lenient parent also reaches cannot
   * leave the enum pairs below to the sub-bridge. Asked once every parent is known, so the answer
   * does not depend on which parent was processed first. A sub-pair with a {@code @Bridge} of its
   * own is never lenient by reach, and its own declaration decides what lies below it.
   */
  private void refuseStrictReferencesToLenientPairs() {
    for (final var entry : strictReferences.entrySet()) {
      if (!lenientPairs.contains(entry.getKey()) || configsByPair.containsKey(entry.getKey())) continue;
      for (final var reference : entry.getValue()) {
        final var refusal = bothWaysRefusal(
          reference.srcType(),
          reference.tgtType(),
          reference.fieldName(),
          new HashSet<>()
        );
        if (refusal == null) continue;
        error(
          reference.source(),
          "@Bridge " + reference.source().getSimpleName() + " -> " + reference.target().getSimpleName() + ": " + refusal
        );
      }
    }
  }

  /**
   * The refusal a mapper converting both ways makes at or below a pair where one converting forward
   * only does not, or null. The walk follows properties by name, as the runtime mapper recurses,
   * and stops at a record or bean pair with a {@code @Bridge} of its own, whose renames, transforms
   * and drops decide its fields. {@code seen} holds the pairs already walked, so a type that
   * reaches itself is walked once.
   */
  private String bothWaysRefusal(
    final TypeMirror srcType,
    final TypeMirror tgtType,
    final String name,
    final Set<String> seen
  ) {
    final var decision = rules.decidePair(srcType, tgtType, name, false);
    if (decision instanceof PairDecision.Incompatible<TypeMirror> refused) {
      return rules.decidePair(srcType, tgtType, name, true) instanceof PairDecision.Incompatible
        ? null
        : refused.message();
    }
    return switch (decision) {
      case PairDecision.RecursePair<TypeMirror> __ -> {
        final var pair = new TypePair(qualifiedName(srcType), qualifiedName(tgtType));
        if (configsByPair.containsKey(pair) || !seen.add(srcType + " -> " + tgtType)) yield null;
        final var targetFields = fieldsOf((TypeElement) ((DeclaredType) tgtType).asElement());
        for (final var sf : fieldsOf((TypeElement) ((DeclaredType) srcType).asElement())) {
          for (final var tf : targetFields) {
            if (!tf.name().equals(sf.name())) continue;
            final var below = bothWaysRefusal(sf.type(), tf.type(), sf.name(), seen);
            if (below != null) yield below;
          }
        }
        yield null;
      }
      case PairDecision.OptionalToNullable<TypeMirror> d -> bothWaysRefusal(
        d.elementSrc(),
        d.elementTgt(),
        name + "[*]",
        seen
      );
      case PairDecision.NullableToOptional<TypeMirror> d -> bothWaysRefusal(
        d.elementSrc(),
        d.elementTgt(),
        name + "[*]",
        seen
      );
      case PairDecision.LiftContainer<TypeMirror> d -> bothWaysRefusal(
        d.src().elementType(),
        d.tgt().elementType(),
        name + "[*]",
        seen
      );
      default -> null;
    };
  }

  /**
   * Plan an enum pair as a nested class converting constant to constant by name, and return the
   * class's name, or null after refusing a pair the shared spec does not convert. A lenient bridge
   * converts forward only as far as the spec is concerned, since its backward is documented as
   * lossy: a target constant with no source counterpart converts back to null.
   */
  private String planEnum(
    final TypeElement source,
    final TypeElement target,
    final String fieldName,
    final TypeMirror srcType,
    final TypeMirror tgtType,
    final boolean lenient
  ) {
    final var decision = rules.decidePair(srcType, tgtType, fieldName, lenient);
    if (!(decision instanceof PairDecision.EnumByName)) {
      final var reason =
        decision instanceof PairDecision.Incompatible<TypeMirror> refused
          ? refused.message()
          : "the shared pairing rules decided " + decision + " for an enum pair";
      error(source, "@Bridge " + source.getSimpleName() + " -> " + target.getSimpleName() + ": " + reason);
      return null;
    }
    final var name = "__Nested" + nestedElements.size();
    nestedElements.put(name, new NestedElement(srcType, tgtType, FieldPlan.ofKind(FieldPlan.Kind.ENUM, name)));
    return name;
  }

  /**
   * An element that is itself a container, converted by a generated nested class whose {@code
   * forward} and {@code backward} each rebuild one inner container. The outer helper calls it the
   * way it calls a sub-bridge, so containers nest to any depth without a helper of their own kind.
   */
  private record NestedElement(TypeMirror src, TypeMirror tgt, FieldPlan plan, boolean copied) {
    NestedElement(final TypeMirror src, final TypeMirror tgt, final FieldPlan plan) {
      this(src, tgt, plan, false);
    }
  }

  /** The nested element classes the pair being planned needs, by class name. */
  private final Map<String, NestedElement> nestedElements = new LinkedHashMap<>();

  /** The methods each nested element class of a lifted {@code @ViaMapper} calls, by its name. */
  private final Map<String, ViaLift> viaElementMethods = new HashMap<>();

  private FieldPlan planNestedElement(
    final TypeElement parentSource,
    final TypeElement parentTarget,
    final String fieldName,
    final TypeMirror srcElement,
    final TypeMirror tgtElement,
    final ContainerShape innerSrc,
    final ContainerShape innerTgt,
    final FieldPlan.Kind kind,
    final Deque<TypePair> pending,
    final Set<TypePair> seen,
    final boolean lenient,
    final String parentPkg
  ) {
    if (innerSrc.kind() == FieldPlan.Kind.MAP_VALUES && !isSameType(innerSrc.keyType(), innerTgt.keyType())) {
      error(
        parentSource,
        "@Bridge " +
          parentSource.getSimpleName() +
          " -> " +
          parentTarget.getSimpleName() +
          ": container field '" +
          fieldName +
          "' has nested Map key types that differ — " +
          innerSrc.keyType() +
          " vs " +
          innerTgt.keyType() +
          ". Map key types must match exactly; codegen preserves source keys."
      );
      return null;
    }
    final var inner = planElementSubBridge(
      parentSource,
      parentTarget,
      fieldName,
      innerSrc.elementType(),
      innerTgt.elementType(),
      innerSrc.kind(),
      pending,
      seen,
      lenient,
      parentPkg
    );
    if (inner == null) return null;
    final var unassignable = firstUnassignableContainer(srcElement, tgtElement, inner.kind(), parentPkg);
    if (unassignable != null) {
      error(parentSource, refusedContainerMessage(parentSource, parentTarget, fieldName, unassignable));
      return null;
    }
    final var unnameable = firstUnnameableContainer(srcElement, tgtElement, parentPkg);
    if (unnameable != null) {
      error(parentSource, unnameableContainerMessage(parentSource, parentTarget, fieldName, unnameable, parentPkg));
      return null;
    }
    // An inner container whose elements pass through is copied by constructor where each side can
    // be built from the other, as a field is and as the reflective path does at any depth. Every
    // other inner container is filled through a helper, which has to be able to allocate it.
    final var copied =
      IDENTITY_ELEMENT_SENTINEL.equals(inner.subBridgeName()) &&
      copyConstructs(tgtElement, inner.kind(), srcElement, parentPkg) &&
      copyConstructs(srcElement, inner.kind(), tgtElement, parentPkg);
    final var badAlloc = copied ? null : firstNonAllocatableContainer(srcElement, tgtElement, inner.kind(), parentPkg);
    if (badAlloc != null) {
      error(parentSource, refusedContainerMessage(parentSource, parentTarget, fieldName, badAlloc));
      return null;
    }
    final var name = "__Nested" + nestedElements.size();
    nestedElements.put(
      name,
      new NestedElement(
        srcElement,
        tgtElement,
        inner.withContainerImpls(concreteImplFqn(tgtElement, inner.kind()), concreteImplFqn(srcElement, inner.kind())),
        copied
      )
    );
    // A nested element class whose elements take the active path takes it too, to pass it on.
    if (threadedRefs.contains(inner.subBridgeName())) threadedRefs.add(name);
    return FieldPlan.ofKind(kind, name);
  }

  /**
   * One direction of a nested element class that converts each element through {@code method}, a
   * {@code @ViaMapper}'s {@code forward} or {@code backward}: a null element stays null, since the
   * mapper is user code with no promise to take one, and any other is handed over cast to the
   * method's parameter where the element is held as a wider type.
   */
  private void emitViaElementDirection(
    final PrintWriter out,
    final ExecutableElement method,
    final TypeMirror from,
    final TypeMirror to,
    final String mapper
  ) {
    final var types = processingEnv.getTypeUtils();
    final var parameter = parameterOf(method);
    final var handed = types.isSameType(parameter, types.erasure(from)) ? "in" : "(" + parameter + ") in";
    final var direction = method.getSimpleName().toString();
    out.println(
      "    static " +
        to +
        " " +
        direction +
        "(final " +
        from +
        " in) { return in == null ? null : " +
        mapper +
        "." +
        direction +
        "(" +
        handed +
        "); }"
    );
  }

  /** The erasure of the type {@code method}'s one parameter takes. */
  private TypeMirror parameterOf(final ExecutableElement method) {
    return processingEnv.getTypeUtils().erasure(method.getParameters().getFirst().asType());
  }

  /**
   * Writes each nested element class: its {@code forward} rebuilds one inner source container as
   * the target's, and {@code backward} the reverse, through the same helpers a field uses.
   */
  private void emitNestedElements(
    final PrintWriter out,
    final Map<String, NestedElement> nested,
    final String bridgePkg
  ) {
    for (final var entry : nested.entrySet()) {
      final var element = entry.getValue();
      final var plan = element.plan();
      out.println();
      out.println("  private static final class " + entry.getKey() + " {");
      if (plan.kind() == FieldPlan.Kind.ENUM) {
        emitEnumDirection(out, "forward", element.src(), element.tgt());
        emitEnumDirection(out, "backward", element.tgt(), element.src());
      } else if (plan.kind() == FieldPlan.Kind.COPY) {
        for (final var direction : List.of("forward", "backward")) {
          out.println(
            "    static " +
              element.src() +
              " " +
              direction +
              "(final " +
              element.src() +
              " in) { return " +
              containerCopy("in", plan) +
              "; }"
          );
        }
      } else if (plan.userSuppliedBridge()) {
        final var lift = viaElementMethods.get(entry.getKey());
        emitViaElementDirection(out, lift.forward(), element.src(), element.tgt(), plan.subBridgeName());
        emitViaElementDirection(out, lift.backward(), element.tgt(), element.src(), plan.subBridgeName());
      } else if (IDENTITY_ELEMENT_SENTINEL.equals(plan.subBridgeName())) {
        emitNestedDirection(
          out,
          "forward",
          element.src(),
          element.tgt(),
          plan,
          plan.fwdContainerImpl(),
          element.copied(),
          bridgePkg
        );
        emitNestedDirection(
          out,
          "backward",
          element.tgt(),
          element.src(),
          plan,
          plan.bwdContainerImpl(),
          element.copied(),
          bridgePkg
        );
      } else {
        switch (plan.kind()) {
          case LIST -> {
            emitListHelper(out, "forward", element.src(), element.tgt(), plan.subBridgeName(), "forward", bridgePkg);
            emitListHelper(out, "backward", element.tgt(), element.src(), plan.subBridgeName(), "backward", bridgePkg);
          }
          case SET -> {
            emitSetHelper(out, "forward", element.src(), element.tgt(), plan.subBridgeName(), "forward", bridgePkg);
            emitSetHelper(out, "backward", element.tgt(), element.src(), plan.subBridgeName(), "backward", bridgePkg);
          }
          case MAP_VALUES -> {
            emitMapHelper(out, "forward", element.src(), element.tgt(), plan.subBridgeName(), "forward", bridgePkg);
            emitMapHelper(out, "backward", element.tgt(), element.src(), plan.subBridgeName(), "backward", bridgePkg);
          }
          default -> throw new IllegalStateException("a nested element of kind " + plan.kind());
        }
      }
      out.println("  }");
    }
  }

  /**
   * One direction of an enum pair: a switch sending each constant of {@code from} to the constant
   * of {@code to} with its name, and null to null. A constant with no counterpart, which only the
   * backward direction of a lenient bridge can meet, converts to null. Every other switch ends in a
   * default that throws, which only a constant added to {@code from} after this bridge was
   * generated reaches. Both enums are named by their qualified names, since a type-use annotation
   * on the declared type is part of how that type prints and cannot qualify a constant.
   */
  private void emitEnumDirection(
    final PrintWriter out,
    final String direction,
    final TypeMirror from,
    final TypeMirror to
  ) {
    final var fromName = qualifiedName(from);
    final var toName = qualifiedName(to);
    final var unmatched = new HashSet<>(rules.enumCorrespondence(from, to).missingOnTarget());
    out.println("    static " + toName + " " + direction + "(final " + fromName + " in) {");
    out.println("      if (in == null) return null;");
    out.println("      return switch (in) {");
    for (final var constant : rules.enumConstants(from)) {
      if (!unmatched.contains(constant)) out.println(
        "        case " + constant + " -> " + toName + "." + constant + ";"
      );
    }
    if (unmatched.isEmpty()) {
      out.println(
        "        default -> throw new IllegalArgumentException(\"" +
          fromName +
          " constant \" + in.name() + \" has no counterpart in " +
          toName +
          "; re-run the annotation processor\");"
      );
    } else {
      out.println("        default -> null;");
    }
    out.println("      };");
    out.println("    }");
  }

  /** The qualified name of a declared type's class, without its type-use annotations. */
  private static String qualifiedName(final TypeMirror type) {
    return ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
  }

  /**
   * One direction of a nested element whose inner elements pass through: handed to the output's
   * copy constructor where the pair is copied, as a field's inline copy is, and filled by the
   * self-contained helper otherwise, which an output that keeps an order always is.
   */
  private void emitNestedDirection(
    final PrintWriter out,
    final String direction,
    final TypeMirror from,
    final TypeMirror to,
    final FieldPlan plan,
    final String outputImpl,
    final boolean copied,
    final String bridgePkg
  ) {
    if (!copied || copiesThroughHelper(plan, outputImpl)) {
      emitRawContainerHelper(out, direction, from, to, plan, direction, bridgePkg);
      return;
    }
    out.println();
    out.println(
      "  private static " +
        to +
        " " +
        direction +
        "(final " +
        from +
        " src) { return src == null ? null : new " +
        withDiamond(requireImpl(outputImpl, direction)) +
        "(src); }"
    );
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

  /** The class name an inline copy allocates, with the diamond only where the class is generic. */
  private String withDiamond(final String implFqn) {
    return implFqn + typeArgumentClause(implFqn, "");
  }

  // The BridgeFn<A, B> instantiation in `usingType`'s supertype closure, or null when the class
  // does not implement BridgeFn at all. The erasure comparison finds the interface regardless of
  // how many levels up the hierarchy it sits.
  private DeclaredType bridgeFnInstantiation(final TypeMirror usingType) {
    final var types = processingEnv.getTypeUtils();
    final var fnEl = processingEnv.getElementUtils().getTypeElement(BRIDGE_FN_FQN);
    if (fnEl == null) return null;
    final var erasedFn = types.erasure(fnEl.asType());
    final Deque<TypeMirror> work = new ArrayDeque<>();
    work.add(usingType);
    final var seen = new HashSet<String>();
    while (!work.isEmpty()) {
      final var t = work.poll();
      if (t.getKind() != TypeKind.DECLARED) continue;
      if (!seen.add(t.toString())) continue;
      if (types.isSameType(types.erasure(t), erasedFn)) return (DeclaredType) t;
      work.addAll(types.directSupertypes(t));
    }
    return null;
  }

  /**
   * Whether the generated bridge could call this member. It subclasses nothing, so private is out,
   * protected is out unless the member happens to share the bridge's package, and package-private
   * is in only when it does. Which package that is depends on the pairing's form -- a carrier emits
   * beside the carrier, everything else beside the source -- so the caller supplies it rather than
   * this deriving it.
   */
  private boolean bindableFrom(final ExecutableElement m, final PackageElement callerPackage) {
    final var mods = m.getModifiers();
    if (mods.contains(Modifier.PRIVATE)) return false;
    if (mods.contains(Modifier.PUBLIC)) return true;
    return processingEnv.getElementUtils().getPackageOf(m).equals(callerPackage);
  }

  /**
   * Whether the generated bridge, emitted into {@code bridgePkg}, can name this type. A nested type
   * is named through every type enclosing it, so each of them has to be reachable by the rule
   * {@link #bindableFrom} applies to a member: private is out, public is in, and anything else is
   * in only from its own package. A public class nested in one that is not public is therefore
   * nameable beside it and nowhere else.
   */
  private boolean nameableFrom(final TypeElement type, final String bridgePkg) {
    for (Element el = type; el instanceof TypeElement enclosing; el = enclosing.getEnclosingElement()) {
      final var mods = enclosing.getModifiers();
      if (mods.contains(Modifier.PRIVATE)) return false;
      if (mods.contains(Modifier.PUBLIC)) continue;
      if (!processingEnv.getElementUtils().getPackageOf(enclosing).getQualifiedName().contentEquals(bridgePkg)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The member the emitted call site will actually bind to, as a signature rather than as a name.
   * Asked of a transform's {@code forward} and {@code backward}, and of a target's setters, which
   * are bound the same way: the emission writes a name and Java picks the overload. Four things
   * decide it, and reading the interface's type arguments gets all four wrong.
   *
   * <p>A member inherited from a generic base carries that base's type variables, which mean
   * nothing on their own — {@code asMemberOf} substitutes them for what the subclass fixed them to,
   * so a {@code backward} declared to return {@code T} reads as returning {@code Integer}.
   *
   * <p>Erasure applies only where the emitter writes a raw instantiation, which it does exactly
   * when the using class is generic. Erasing unconditionally discards the parameterisation a
   * non-generic class keeps at the call site, which lets a mismatched {@code List<String>} through
   * to fail inside the generated file.
   *
   * <p>Java resolves a call in phases and stops at the first that yields a candidate: the first
   * admits subtyping and widening primitive conversion, the second adds boxing and unboxing. So an
   * {@code int} binds {@code forward(long)} without {@code forward(Integer)} ever being weighed
   * against it — which is as well, since a primitive and a wrapper are unrelated by subtyping and
   * the question of which is more specific has no answer.
   *
   * <p>Within that phase the most specific parameter wins, which is the rule javac applies at the
   * call site. Where no candidate is more specific than all the others, javac calls the call site
   * ambiguous rather than choosing, and so does this.
   *
   * @param argType what the call site will pass, so inapplicable overloads are discarded first
   * @return what the call site binds, that no overload applies, or that two apply equally
   */
  private Resolution resolvedFn(
    final TypeElement declaringType,
    final String name,
    final TypeMirror argType,
    final PackageElement callerPackage
  ) {
    final var types = processingEnv.getTypeUtils();
    final var owner = (DeclaredType) declaringType.asType();
    final var raw = !declaringType.getTypeParameters().isEmpty();
    final var applicable = new ArrayList<ExecutableType>();
    final var strict = new ArrayList<ExecutableType>();
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(declaringType))) {
      if (!m.getSimpleName().contentEquals(name) || m.getParameters().size() != 1) continue;
      if (m.getModifiers().contains(Modifier.ABSTRACT)) continue;
      // getAllMembers includes members the generated bridge is in no position to call. Selecting
      // one says a row fits on the strength of a method javac will not bind, and the overload it
      // does bind is then the one that fails.
      if (!bindableFrom(m, callerPackage)) continue;
      final var seen = (ExecutableType) types.asMemberOf(owner, m);
      final var param = paramOf(seen, raw);
      if (!types.isAssignable(argType, param)) continue;
      applicable.add(seen);
      // Java resolves a call in phases and stops at the first that yields a candidate: the first
      // admits subtyping and primitive widening, the second adds boxing and unboxing. So an int
      // argument binds forward(long) even though forward(Integer) is also applicable, and a check
      // that weighs the two together picks by specificity -- a question neither answers, since a
      // primitive and a wrapper are unrelated by subtyping.
      if (argType.getKind().isPrimitive() == param.getKind().isPrimitive()) strict.add(seen);
    }
    final var phase = strict.isEmpty() ? applicable : strict;
    if (phase.isEmpty()) return new Resolution.None();

    // The most specific candidate is the one whose parameter every other candidate's parameter is
    // not more specific than. Where two are mutually unrelated there is no such candidate, and
    // javac calls the call site ambiguous rather than picking one -- so keeping the first seen
    // makes the verdict depend on declaration order and describes a call that will not compile.
    var best = phase.getFirst();
    for (final var candidate : phase) {
      if (types.isSubtype(types.erasure(paramOf(candidate, raw)), types.erasure(paramOf(best, raw)))) best = candidate;
    }
    final var bestParam = types.erasure(paramOf(best, raw));
    for (final var candidate : phase) {
      final var param = types.erasure(paramOf(candidate, raw));
      if (!types.isSameType(param, bestParam) && !types.isSubtype(bestParam, param)) {
        return new Resolution.Ambiguous(bestParam, param);
      }
    }
    return new Resolution.Bound(raw ? (ExecutableType) types.erasure(best) : best);
  }

  /**
   * The type a transform's call returns for an argument of {@code argument}: the bound overload's
   * return type, or, for a {@code BridgeFn} with none bound, its type argument at {@code
   * fnArgument}. Null where neither says.
   */
  private TypeMirror transformGives(
    final TypeElement usingEl,
    final String name,
    final TypeMirror argument,
    final PackageElement callerPackage,
    final boolean bridgeFn,
    final int fnArgument
  ) {
    if (resolvedFn(usingEl, name, argument, callerPackage) instanceof Resolution.Bound bound) {
      return bound.member().getReturnType();
    }
    if (!bridgeFn) return null;
    final var fn = bridgeFnInstantiation(usingEl.asType());
    if (fn == null || fn.getTypeArguments().size() != 2) return null;
    return erasedBound(fn.getTypeArguments().get(fnArgument));
  }

  private TypeMirror paramOf(final ExecutableType m, final boolean raw) {
    final var param = m.getParameterTypes().getFirst();
    return raw ? processingEnv.getTypeUtils().erasure(param) : param;
  }

  /** What the emitted call site will bind to, or why it will not bind. */
  private sealed interface Resolution {
    /** Exactly one overload is applicable and most specific; this is what the call site binds. */
    record Bound(ExecutableType member) implements Resolution {}

    /** No overload applies, so the interface's own type arguments still describe the direction. */
    record None() implements Resolution {}

    /** Two overloads apply equally. Java refuses such a call, so no fallback describes it. */
    record Ambiguous(TypeMirror a, TypeMirror b) implements Resolution {}
  }

  // A type-variable argument stands for whatever the emitter's raw call site erases it to, so the
  // fit test compares against that bound; every other type passes through unchanged.
  private TypeMirror erasedBound(final TypeMirror t) {
    if (t.getKind() != TypeKind.TYPEVAR) return t;
    return processingEnv.getTypeUtils().erasure(t);
  }

  /**
   * The type the bridge holds a value of {@code type} as: its erasure where it names a type
   * variable, and {@code type} itself otherwise. Every method of a bridge is static, so it can name
   * no type variable, a class's or a method's: a member typed by its class's variable is reached
   * through the raw class and takes and returns the erasure, and a generic method's variable is
   * inferred from what the bridge passes or expects. A member a class with no type parameters
   * declares with a superclass's variable is read as a member of that class first, so its type
   * names the class the variable is fixed to. Null for null.
   */
  private TypeMirror heldAs(final TypeMirror type) {
    if (type == null || !MirrorProps.typeVariableIn(type)) return type;
    return processingEnv.getTypeUtils().erasure(type);
  }

  /**
   * Per source property a {@code @ViaMapper} converts, the class its {@code forward} and its {@code
   * backward} call cast their argument to, where the call takes the value only through a cast.
   */
  private record ViaCasts(Map<String, String> forwardCasts, Map<String, String> backwardCasts) {}

  /**
   * A {@code @ViaMapper} whose mapper converts the elements of a container property rather than the
   * container: the mapper's {@code forward} that takes the source's element, and its {@code
   * backward} that takes the target's; or, with no methods, the refusal of a row whose element
   * method cannot be chosen.
   */
  private record ViaLift(ExecutableElement forward, ExecutableElement backward, String refusal) {}

  /**
   * The casts each {@code @ViaMapper} call needs, or null after refusing a row by the property's
   * name. Only a property whose type names a type variable on either side is checked here: the
   * bridge holds it as {@link #reachedAs the type it reaches it as}, which may be wider than the
   * mapper's parameter. Its {@code forward} is handed the source's value and its result is written
   * through the target's member, and {@code backward} the other way round. A value the parameter
   * takes is passed as it is; one the parameter's erasure narrows is cast to that erasure, which
   * checks the class at the call; any other value, or a result the member it is written through
   * cannot take, refuses the row.
   */
  private ViaCasts viaCalls(
    final TypeElement source,
    final TypeElement target,
    final List<Field> sourceFields,
    final List<Field> targetFields,
    final PairedFields paired,
    final Map<String, String> renames,
    final Map<String, String> viaMappers,
    final Map<String, ViaLift> viaLifts,
    final Rebuild sourceRebuild,
    final Rebuild targetRebuild,
    final PackageElement bridgePackage
  ) {
    final var forwardCasts = new HashMap<String, String>();
    final var backwardCasts = new HashMap<String, String>();
    for (final var entry : viaMappers.entrySet()) {
      final var field = entry.getKey();
      if (viaLifts.containsKey(field)) continue;
      final var tgtName = renames.getOrDefault(field, field);
      if (!hasField(sourceFields, field) || !hasField(targetFields, tgtName)) continue;
      final var sourceOwn = fieldByName(sourceFields, field).type();
      final var targetOwn = fieldByName(targetFields, tgtName).type();
      final var mapper = processingEnv.getElementUtils().getTypeElement(entry.getValue());
      final var unfit = viaNeverFits(source, target, field, tgtName, mapper, sourceOwn, targetOwn, bridgePackage);
      if (unfit != null) {
        error(source, unfit);
        return null;
      }
      final var sourceHeld = fieldByName(paired.source(), field).type();
      final var targetHeld = fieldByName(paired.target(), tgtName).type();
      final var variable = MirrorProps.typeVariableIn(sourceOwn) || MirrorProps.typeVariableIn(targetOwn);
      // A property no type variable names is handed to the mapper as javac binds it. Where javac
      // binds no method, the value is cast or told apart by its class to reach one, or the row is
      // refused by name.
      // The bridge calls a mapper's methods on its class, so a name it declares only as an
      // instance method is one it cannot call, though javac would bind it on an instance.
      for (final var name : List.of("forward", "backward")) {
        if (viaCandidates(mapper, name, bridgePackage).isEmpty()) {
          error(source, unboundRefusal(field, mapper, name, sourceOwn, sourceHeld, bridgePackage));
          return null;
        }
      }
      if (!variable && bindsAsHeld(mapper, sourceHeld, targetHeld, bridgePackage)) continue;
      final var forward = viaCall(mapper, "forward", sourceOwn, sourceHeld, bridgePackage);
      final var refusal =
        forward == null
          ? unboundRefusal(field, mapper, "forward", sourceOwn, sourceHeld, bridgePackage)
          : viaResultRefusal(field, mapper, "forward", forward, source, target, targetRebuild, tgtName, bridgePackage);
      if (refusal != null) {
        error(source, refusal);
        return null;
      }
      final var backward = viaCall(mapper, "backward", targetOwn, targetHeld, bridgePackage);
      final var backRefusal =
        backward == null
          ? unboundRefusal(field, mapper, "backward", targetOwn, targetHeld, bridgePackage)
          : viaResultRefusal(field, mapper, "backward", backward, source, target, sourceRebuild, field, bridgePackage);
      if (backRefusal != null) {
        error(source, backRefusal);
        return null;
      }
      if (forward.cast() != null) forwardCasts.put(field, forward.cast());
      if (backward.cast() != null) backwardCasts.put(field, backward.cast());
    }
    return new ViaCasts(forwardCasts, backwardCasts);
  }

  /**
   * Whether javac binds a {@code forward} and a {@code backward} to the values as the bridge holds
   * them.
   */
  private boolean bindsAsHeld(
    final TypeElement mapper,
    final TypeMirror sourceHeld,
    final TypeMirror targetHeld,
    final PackageElement bridgePackage
  ) {
    return (
      resolvedFn(mapper, "forward", sourceHeld, bridgePackage) instanceof Resolution.Bound &&
      resolvedFn(mapper, "backward", targetHeld, bridgePackage) instanceof Resolution.Bound
    );
  }

  /**
   * The {@code @ViaMapper} rows whose mapper converts the elements of a container property, by
   * source property, each with the methods it lifts; see {@link #viaLift}. Null after refusing a
   * row whose element method cannot be chosen. Every row reaching here names a property both sides
   * have and a class that resolves: the rest are refused or retried before a pair is emitted.
   */
  private Map<String, ViaLift> viaLifts(
    final TypeElement source,
    final List<Field> sourceFields,
    final List<Field> targetFields,
    final Map<String, String> renames,
    final Map<String, String> viaMappers,
    final PackageElement bridgePackage
  ) {
    final var lifts = new HashMap<String, ViaLift>();
    for (final var entry : viaMappers.entrySet()) {
      final var field = entry.getKey();
      final var lift = viaLift(
        field,
        processingEnv.getElementUtils().getTypeElement(entry.getValue()),
        fieldByName(sourceFields, field).type(),
        fieldByName(targetFields, renames.getOrDefault(field, field)).type(),
        bridgePackage
      );
      if (lift == null) continue;
      if (lift.refusal() != null) {
        error(source, lift.refusal());
        return null;
      }
      lifts.put(field, lift);
    }
    return lifts;
  }

  /**
   * How a {@code @ViaMapper} on a pair of containers converts their elements, or null when its
   * mapper converts the property itself. The two sides must be containers of one kind, List, Set,
   * Optional or a map's values, and the mapper must declare a {@code forward} taking an element of
   * the source's container and a {@code backward} taking an element of the target's: the reflective
   * path lifts a {@code via} row's mapper over a container on the same terms. An element that is a
   * class is the parameter's class; one typed by a variable is one whose bound admits the
   * parameter, unless the parameter takes the container itself. A mapper whose {@code forward} and
   * {@code backward} each have an overload converting the whole property, taking one side's
   * container and giving what the other side's property holds, converts the property as it is and
   * is not lifted. Where more than one method of a name takes the element, the row is refused,
   * since the one an element's class calls for cannot be chosen when the bridge is compiled.
   */
  private ViaLift viaLift(
    final String field,
    final TypeElement mapper,
    final TypeMirror sourceOwn,
    final TypeMirror targetOwn,
    final PackageElement bridgePackage
  ) {
    final var srcShape = containerShapeOf(sourceOwn, targetOwn);
    final var tgtShape = containerShapeOf(targetOwn, sourceOwn);
    if (srcShape == null || tgtShape == null || srcShape.kind() != tgtShape.kind()) return null;
    if (
      convertsWhole(mapper, "forward", sourceOwn, targetOwn, bridgePackage) &&
      convertsWhole(mapper, "backward", targetOwn, sourceOwn, bridgePackage)
    ) {
      return null;
    }
    final var forwards = elementMethods(mapper, "forward", srcShape.elementType(), sourceOwn, bridgePackage);
    final var backwards = elementMethods(mapper, "backward", tgtShape.elementType(), targetOwn, bridgePackage);
    if (forwards.isEmpty() || backwards.isEmpty()) return null;
    if (forwards.size() > 1 || backwards.size() > 1) {
      final var name = forwards.size() > 1 ? "forward" : "backward";
      final var container = forwards.size() > 1 ? sourceOwn : targetOwn;
      return new ViaLift(
        null,
        null,
        viaRefusal(
          field,
          mapper,
          name,
          "has several overloads that take an element of " +
            container +
            ", so the one an element's class calls for cannot be chosen when the bridge is compiled"
        )
      );
    }
    return new ViaLift(forwards.getFirst(), backwards.getFirst(), null);
  }

  /**
   * The refusal of a {@code @ViaMapper} not lifted over its property's elements whose mapper is
   * handed a property no value of which it can take, or null. The source's property is handed to
   * {@code forward} and the target's to {@code backward}; a side is refused when no method of that
   * name takes a class a value of the property can be an instance of, as the reflective path
   * refuses the same row, with the same message, naming the first such method's parameter.
   */
  private String viaNeverFits(
    final TypeElement source,
    final TypeElement target,
    final String field,
    final String tgtName,
    final TypeElement mapper,
    final TypeMirror sourceOwn,
    final TypeMirror targetOwn,
    final PackageElement bridgePackage
  ) {
    final var forward = viaSideNeverFits(mapper, "forward", sourceOwn, bridgePackage);
    if (forward != null) {
      return PairingMessages.viaMapperNeverFits(
        source.getSimpleName().toString(),
        target.getSimpleName().toString(),
        "source",
        field,
        forward
      );
    }
    final var backward = viaSideNeverFits(mapper, "backward", targetOwn, bridgePackage);
    return backward == null
      ? null
      : PairingMessages.viaMapperNeverFits(
          source.getSimpleName().toString(),
          target.getSimpleName().toString(),
          "target",
          tgtName,
          backward
        );
  }

  /**
   * The simple name of the class {@code mapper}'s first {@code name} method takes, when no method
   * of that name takes a class a value of {@code own} can be an instance of, a primitive on either
   * side counting as its wrapper; null otherwise, and for a mapper with no such method.
   */
  private String viaSideNeverFits(
    final TypeElement mapper,
    final String name,
    final TypeMirror own,
    final PackageElement bridgePackage
  ) {
    final var takes = viaCandidates(mapper, name, bridgePackage)
      .stream()
      .map(m -> boxedErasure(m.getParameters().getFirst().asType()))
      .toList();
    if (takes.isEmpty()) return null;
    final var held = boxedErasure(own);
    if (takes.stream().anyMatch(parameter -> mayShareInstance(held, parameter))) return null;
    final var first = processingEnv.getTypeUtils().asElement(takes.getFirst());
    return first == null ? takes.getFirst().toString() : first.getSimpleName().toString();
  }

  /**
   * The simple name of the class {@code type} names, as {@link Class#getSimpleName} gives it: an
   * array's is its component's followed by {@code []}, and a primitive's is its keyword.
   */
  private String simpleNameOf(final TypeMirror type) {
    if (type instanceof ArrayType array) return simpleNameOf(array.getComponentType()) + "[]";
    final var element = processingEnv.getTypeUtils().asElement(type);
    return element == null ? type.toString() : element.getSimpleName().toString();
  }

  /** {@code type}'s wrapper class where it is a primitive, and its erasure otherwise. */
  private TypeMirror boxedErasure(final TypeMirror type) {
    final var types = processingEnv.getTypeUtils();
    return type.getKind().isPrimitive() ? types.boxedClass((PrimitiveType) type).asType() : types.erasure(type);
  }

  /**
   * Whether one object can be an instance of both {@code a} and {@code b}, erased types, as the
   * reflective path decides it: one extends the other; either is sealed and one of its permitted
   * cases can be such an instance; or one is an interface and the other is an interface or a class
   * that is not final, which a subclass can extend to implement it. An enum is final, or sealed
   * when its constants declare bodies, each of which is final. An array or a primitive shares an
   * instance only with a type it extends or is extended by.
   */
  private boolean mayShareInstance(final TypeMirror a, final TypeMirror b) {
    final var types = processingEnv.getTypeUtils();
    if (types.isSubtype(a, b) || types.isSubtype(b, a)) return true;
    if (!(types.asElement(a) instanceof TypeElement aEl) || !(types.asElement(b) instanceof TypeElement bEl)) {
      return false;
    }
    if (aEl.getModifiers().contains(Modifier.SEALED)) {
      return aEl
        .getPermittedSubclasses()
        .stream()
        .anyMatch(c -> mayShareInstance(types.erasure(c), b));
    }
    if (bEl.getModifiers().contains(Modifier.SEALED)) {
      return bEl
        .getPermittedSubclasses()
        .stream()
        .anyMatch(c -> mayShareInstance(a, types.erasure(c)));
    }
    if (aEl.getKind().isInterface()) return extendableByASubclass(bEl);
    return bEl.getKind().isInterface() && extendableByASubclass(aEl);
  }

  /**
   * Whether a subclass of {@code type} can implement another interface: an interface, or a class
   * that is not final.
   */
  private static boolean extendableByASubclass(final TypeElement type) {
    return !type.getModifiers().contains(Modifier.FINAL);
  }

  /**
   * Whether {@code mapper} has a method named {@code name} that converts the whole property: one
   * taking the {@code from} side's container and giving what the {@code to} side's property holds,
   * as javac binds a call handed the container and written into that property. A result typed by
   * the method's own type variable gives whatever the call site infers it as, so it counts whatever
   * the variable's bound.
   */
  private boolean convertsWhole(
    final TypeElement mapper,
    final String name,
    final TypeMirror from,
    final TypeMirror to,
    final PackageElement bridgePackage
  ) {
    final var types = processingEnv.getTypeUtils();
    return viaCandidates(mapper, name, bridgePackage)
      .stream()
      .anyMatch(
        m ->
          types.isAssignable(types.erasure(from), types.erasure(m.getParameters().getFirst().asType())) &&
          (types.isAssignable(types.erasure(m.getReturnType()), types.erasure(to)) ||
            (inferredResult(m) && !MirrorProps.typeVariableIn(from)))
      );
  }

  /**
   * Whether {@code method} returns a type variable. A {@code @ViaMapper}'s methods are static, so
   * such a variable is the method's own, and javac infers it at the call as the property the call
   * writes, intersected with whatever bound the variable declares, so the call binds whatever that
   * bound is.
   */
  private static boolean inferredResult(final ExecutableElement method) {
    return method.getReturnType().getKind() == TypeKind.TYPEVAR;
  }

  /** The static methods of {@code mapper} named {@code name} that take one {@code element}. */
  private List<ExecutableElement> elementMethods(
    final TypeElement mapper,
    final String name,
    final TypeMirror element,
    final TypeMirror container,
    final PackageElement bridgePackage
  ) {
    final var types = processingEnv.getTypeUtils();
    return viaCandidates(mapper, name, bridgePackage)
      .stream()
      .filter(m -> elementTaken(element, types.erasure(m.getParameters().getFirst().asType()), container))
      .toList();
  }

  /**
   * Whether a container's {@code element} is what a mapper method taking {@code parameter} reads.
   */
  private boolean elementTaken(final TypeMirror element, final TypeMirror parameter, final TypeMirror container) {
    final var types = processingEnv.getTypeUtils();
    if (element.getKind() != TypeKind.TYPEVAR) return types.isSameType(types.erasure(element), parameter);
    return (
      types.isSubtype(parameter, types.erasure(element)) && !types.isSubtype(types.erasure(container), parameter)
    );
  }

  /**
   * A {@code @ViaMapper} call: the class its argument is cast to, or null for none, and its result,
   * or null where the call infers it.
   */
  private record ViaCall(String cast, TypeMirror result) {}

  /**
   * How a call to {@code mapper}'s static {@code name} binds a value of {@code own} type the bridge
   * holds as {@code held}, or null where it binds none. javac chooses among the overloads by the
   * held type, which for a variable with a bound is that bound: the overload a method reference
   * typed by the bound binds as well. A variable with no bound is held as {@code Object}, and the
   * overload for {@code Object} need not be the one the value's own class calls for, so there the
   * call binds only when the mapper declares one method of that name. Where javac binds none, the
   * call binds the one method a value of the property can be an instance of the parameter of, as it
   * is where it takes {@code held}, and otherwise through a cast to its parameter's erasure.
   */
  private ViaCall viaCall(
    final TypeElement mapper,
    final String name,
    final TypeMirror own,
    final TypeMirror held,
    final PackageElement bridgePackage
  ) {
    final var types = processingEnv.getTypeUtils();
    final var candidates = viaCandidates(mapper, name, bridgePackage);
    if (!choosesBlind(own, held, candidates.size())) {
      // A generic method's result depends on what the call infers, which is held to the member
      // where the result is written, so no result is given here for one. A method whose one
      // parameter is its own type variable takes the value as it is.
      if (resolvedFn(mapper, name, held, bridgePackage) instanceof Resolution.Bound bound) {
        return new ViaCall(null, bound.member().getTypeVariables().isEmpty() ? bound.member().getReturnType() : null);
      }
      if (inferringFn(mapper, name, held, bridgePackage) instanceof Resolution.Bound) {
        return new ViaCall(null, null);
      }
    }
    final var reached = candidates
      .stream()
      .filter(m -> mayShareInstance(boxedErasure(held), boxedErasure(m.getParameters().getFirst().asType())))
      .toList();
    if (reached.size() != 1) return null;
    final var parameter = parameterOf(reached.getFirst());
    // A parameter the held type is assignable to takes the value as it is; a cast to it would be
    // one
    // javac reports as redundant.
    final var cast = types.isAssignable(types.erasure(held), parameter) ? null : parameter.toString();
    return new ViaCall(cast, types.erasure(reached.getFirst().getReturnType()));
  }

  /**
   * Every static method of {@code mapper} named {@code name} taking one argument the bridge can
   * call.
   */
  private List<ExecutableElement> viaCandidates(
    final TypeElement mapper,
    final String name,
    final PackageElement bridgePackage
  ) {
    return ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(mapper))
      .stream()
      .filter(m -> m.getSimpleName().contentEquals(name) && m.getParameters().size() == 1)
      .filter(m -> m.getModifiers().contains(Modifier.STATIC) && bindableFrom(m, bridgePackage))
      .toList();
  }

  /**
   * Whether javac would choose among {@code candidates} overloads for a value of the variable
   * {@code own} by nothing but {@code Object}, its erasure where it has no bound.
   */
  private boolean choosesBlind(final TypeMirror own, final TypeMirror held, final int candidates) {
    return (
      candidates > 1 &&
      own.getKind() == TypeKind.TYPEVAR &&
      processingEnv
        .getTypeUtils()
        .isSameType(held, processingEnv.getElementUtils().getTypeElement("java.lang.Object").asType())
    );
  }

  /**
   * The refusal of a {@code @ViaMapper} whose {@code name} binds no method: the class declares no
   * method of that name the bridge can call, or none of them takes the value; see {@link
   * #viaUnbound}.
   */
  private String unboundRefusal(
    final String field,
    final TypeElement mapper,
    final String name,
    final TypeMirror own,
    final TypeMirror held,
    final PackageElement bridgePackage
  ) {
    if (viaCandidates(mapper, name, bridgePackage).isEmpty()) {
      return "@ViaMapper field=\"" + field + "\": " + mapper.getQualifiedName() + " declares no " + name;
    }
    return viaRefusal(field, mapper, name, viaUnbound(mapper, name, own, held, bridgePackage));
  }

  /** Why {@link #viaCall} bound no method, for the refusal. */
  private String viaUnbound(
    final TypeElement mapper,
    final String name,
    final TypeMirror own,
    final TypeMirror held,
    final PackageElement bridgePackage
  ) {
    if (choosesBlind(own, held, viaCandidates(mapper, name, bridgePackage).size())) {
      return (
        "has several overloads, and the bridge holds the value as " +
        heldPhrase(own, held) +
        ", so the overload its class calls for cannot be chosen when the bridge is compiled"
      );
    }
    return "takes no value of " + heldPhrase(own, held);
  }

  /**
   * Why a {@code @ViaMapper} call's result cannot be written through the member that writes {@code
   * property} of the side {@code name} writes, the target for {@code forward} and the source for
   * {@code backward}, or null when it can. A result no value of which can be an instance of the
   * class the member takes is refused as the reflective path refuses a {@code via} row whose mapper
   * returns that class, with the same message.
   */
  private String viaResultRefusal(
    final String field,
    final TypeElement mapper,
    final String name,
    final ViaCall call,
    final TypeElement source,
    final TypeElement target,
    final Rebuild rebuild,
    final String property,
    final PackageElement bridgePackage
  ) {
    if (call.result() == null) return null;
    final var forward = name.equals("forward");
    final var owner = forward ? target : source;
    final var stores = heldAs(writeSlotType(owner, rebuild, property, call.result(), bridgePackage));
    if (processingEnv.getTypeUtils().isAssignable(call.result(), stores)) return null;
    final var returned = boxedErasure(call.result());
    final var taken = boxedErasure(stores);
    if (!mayShareInstance(returned, taken)) {
      return PairingMessages.viaResultNeverFits(
        source.getSimpleName().toString(),
        target.getSimpleName().toString(),
        forward ? "target" : "source",
        property,
        simpleNameOf(returned),
        simpleNameOf(taken)
      );
    }
    return viaRefusal(
      field,
      mapper,
      name,
      "returns " +
        call.result() +
        ", which the member " +
        owner.getSimpleName() +
        " writes " +
        property +
        " through does not take: it takes " +
        stores
    );
  }

  /**
   * A {@code @ViaMapper} call's result as the bridge writes it, and why it cannot be written, or
   * null where it can.
   */
  private record ViaResult(TypeMirror gives, String refusal) {}

  /**
   * How a {@code @ViaMapper}'s {@code name} result is written into {@code property} of {@code
   * owner}: through the member the property's type chooses, as it is or after a check of its class,
   * with type arguments shown to fit the member's. A result typed by the method's own type
   * variables is read with each variable its shape fixes in place, as {@link MethodInstantiation}
   * fixes them, and is refused where no instantiation of them within their bounds fits the member.
   * The result is the declared one where that leaves a variable unfixed, which javac infers at the
   * call.
   */
  private ViaResult viaResult(
    final String field,
    final TypeElement mapper,
    final String name,
    final ExecutableType member,
    final TypeMirror argument,
    final TypeElement owner,
    final Rebuild rebuild,
    final String property,
    final PackageElement bridgePackage
  ) {
    final var declared = member.getReturnType();
    final var slot = heldAs(writeSlotType(owner, rebuild, property, declared, bridgePackage));
    final var chosen = chosenParameter(owner, rebuild, property, slot);
    if (chosen == null || mirrorProps().mentionsTypeVariable(chosen)) return new ViaResult(declared, null);
    final var generic = !member.getTypeVariables().isEmpty();
    final var gives = instantiation().result(member, argument, chosen);
    if (gives == null) {
      return new ViaResult(
        declared,
        viaRefusal(field, mapper, name, "returns " + declared + unfixedTail(owner, property, chosen))
      );
    }
    if (mirrorProps().mentionsTypeVariable(gives)) return new ViaResult(declared, null);
    final var types = processingEnv.getTypeUtils();
    // A call whose result is inferred stays a generic call where it is unboxed into a primitive, so
    // it is written as the declared result, through a check its inference cannot fail.
    final var written = generic && chosen.getKind().isPrimitive() ? declared : gives;
    if (types.isAssignable(gives, chosen) && rules.argumentsFit(gives, chosen)) return new ViaResult(written, null);
    if (reachesByCheck(gives, chosen)) return new ViaResult(written, null);
    final var why = generic
      ? "returns " + declared + unfixedTail(owner, property, chosen)
      : "returns " +
        gives +
        ", which the member " +
        owner.getSimpleName() +
        " writes " +
        property +
        " through does not take: it takes " +
        chosen;
    return new ViaResult(declared, viaRefusal(field, mapper, name, why));
  }

  /**
   * The end of the refusal of a generic method's result that no instantiation of its variables lets
   * {@code owner} write into {@code property} through a member taking {@code chosen}.
   */
  private static String unfixedTail(final TypeElement owner, final String property, final TypeMirror chosen) {
    return (
      ", which no instantiation of its type variables lets the member " +
      owner.getSimpleName() +
      " writes " +
      property +
      " through take: it takes " +
      chosen
    );
  }

  /** The instantiations of a generic method's type variables its result's shape fixes. */
  private MethodInstantiation instantiation() {
    return new MethodInstantiation(processingEnv.getTypeUtils(), mirrorProps());
  }

  /**
   * The one static method {@code name} of {@code declaringType} the bridge can call whose one
   * parameter is a type variable the method declares and whose bound takes {@code argType}, which
   * javac binds by inferring the variable; of several, the one whose variable's bound is a subtype
   * of every other's, which javac finds most specific; null where there is none or no such one. The
   * bridge calls a {@code @ViaMapper}'s methods on its class, so an instance method is none of
   * them.
   */
  private Resolution.Bound inferringFn(
    final TypeElement declaringType,
    final String name,
    final TypeMirror argType,
    final PackageElement callerPackage
  ) {
    final var types = processingEnv.getTypeUtils();
    final var owner = (DeclaredType) declaringType.asType();
    final var found = new ArrayList<ExecutableType>();
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(declaringType))) {
      if (!m.getSimpleName().contentEquals(name) || m.getParameters().size() != 1) continue;
      if (m.getModifiers().contains(Modifier.ABSTRACT) || !bindableFrom(m, callerPackage)) continue;
      if (!m.getModifiers().contains(Modifier.STATIC)) continue;
      final var seen = (ExecutableType) types.asMemberOf(owner, m);
      final var parameter = seen.getParameterTypes().getFirst();
      if (!(parameter instanceof TypeVariable variable) || !seen.getTypeVariables().contains(variable)) continue;
      if (!types.isAssignable(argType, types.erasure(variable.getUpperBound()))) continue;
      found.add(seen);
    }
    final Function<ExecutableType, TypeMirror> bound = m -> types.erasure(m.getParameterTypes().getFirst());
    final var specific = found
      .stream()
      .filter(m -> found.stream().allMatch(other -> other == m || types.isSubtype(bound.apply(m), bound.apply(other))))
      .filter(m ->
        found.stream().noneMatch(other -> other != m && types.isSameType(bound.apply(m), bound.apply(other)))
      )
      .toList();
    return specific.size() == 1 ? new Resolution.Bound(specific.getFirst()) : null;
  }

  private static String viaRefusal(final String field, final TypeElement mapper, final String name, final String why) {
    return "@ViaMapper field=\"" + field + "\": " + mapper.getQualifiedName() + "'s " + name + " " + why;
  }

  /** {@code declared}, followed by the type the bridge holds it as where the two differ. */
  private String heldPhrase(final TypeMirror declared, final TypeMirror held) {
    if (processingEnv.getTypeUtils().isSameType(declared, held)) return declared.toString();
    return declared + " (held as its erasure " + held + ")";
  }

  private String applyForward(final String fieldName, final FieldPlan plan, final String readExpr) {
    // A container the inline copy constructor cannot build correctly routes to its self-contained
    // helper; see copiesThroughHelper.
    final var sub = plan.subBridgeName();
    final var fwdHelper = "__fwd_" + fieldName + "(" + readExpr + (threads(sub) ? ", " + pathArg : "") + ")";
    if (copiesThroughHelper(plan, plan.fwdContainerImpl())) return fwdHelper;
    final boolean elementIdentity = IDENTITY_ELEMENT_SENTINEL.equals(sub);
    final var fwdElement = elementIdentity ? "e -> e" : subFunction(sub, "forward");
    return switch (plan.kind()) {
      case IDENTITY -> readExpr;
      case COPY -> containerCopy(readExpr, plan);
      case PRIM_WRAPPER -> plan.fwdNullDefault() == null
        ? readExpr
        : "(" + readExpr + " == null ? " + plan.fwdNullDefault() + " : " + readExpr + ")";
      case RECURSE, ENUM -> subCall(sub, "forward", readExpr);
      // LIST/SET/MAP_VALUES: when the element type needs a sub-bridge, delegate to a private static
      // helper emitted alongside this method (see emitContainerHelpers below). The helper inlines a
      // size-presized for-loop, eliminating the Stream + Spliterator + collector overhead at the
      // dispatch site. When the element type is identity (same on both sides), a defensive copy is
      // sufficient and we emit it inline.
      case LIST -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          withDiamond(requireImpl(plan.fwdContainerImpl(), fieldName)) +
          "(" +
          readExpr +
          "))"
        : fwdHelper;
      case SET -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          withDiamond(requireImpl(plan.fwdContainerImpl(), fieldName)) +
          "(" +
          readExpr +
          "))"
        : fwdHelper;
      case OPTIONAL -> "(" + readExpr + " == null ? null : " + readExpr + ".map(" + fwdElement + "))";
      case MAP_VALUES -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          withDiamond(requireImpl(plan.fwdContainerImpl(), fieldName)) +
          "(" +
          readExpr +
          "))"
        : fwdHelper;
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
        : "__tx_" + fieldName + ".forward(" + checkedArgument(forwardArguments.get(fieldName), readExpr) + ")";
    };
  }

  private String applyBackward(final String fieldName, final FieldPlan plan, final String readExpr) {
    final var sub = plan.subBridgeName();
    final var bwdHelper = "__bwd_" + fieldName + "(" + readExpr + (threads(sub) ? ", " + pathArg : "") + ")";
    if (copiesThroughHelper(plan, plan.bwdContainerImpl())) return bwdHelper;
    final boolean elementIdentity = IDENTITY_ELEMENT_SENTINEL.equals(sub);
    final var bwdElement = elementIdentity ? "e -> e" : subFunction(sub, "backward");
    return switch (plan.kind()) {
      case IDENTITY -> readExpr;
      case COPY -> containerCopy(readExpr, plan);
      case PRIM_WRAPPER -> plan.bwdNullDefault() == null
        ? readExpr
        : "(" + readExpr + " == null ? " + plan.bwdNullDefault() + " : " + readExpr + ")";
      case RECURSE, ENUM -> subCall(sub, "backward", readExpr);
      case LIST, SET, MAP_VALUES -> elementIdentity
        ? "(" +
          readExpr +
          " == null ? null : new " +
          withDiamond(requireImpl(plan.bwdContainerImpl(), fieldName)) +
          "(" +
          readExpr +
          "))"
        : bwdHelper;
      case OPTIONAL -> "(" + readExpr + " == null ? null : " + readExpr + ".map(" + bwdElement + "))";
      // For the cross-paradigm bridges, forward and backward are mirror images.
      case OPTIONAL_TO_NULLABLE -> "Optional.ofNullable(" + readExpr + ").map(" + bwdElement + ")";
      case NULLABLE_TO_OPTIONAL -> "(" +
      readExpr +
      " == null ? null : " +
      readExpr +
      ".map(" +
      bwdElement +
      ").orElse(null))";
      case TRANSFORM -> "__tx_" +
      fieldName +
      ".backward(" +
      checkedArgument(backwardArguments.get(fieldName), readExpr) +
      ")";
    };
  }

  /**
   * Compute the {@code java.util.*} imports a bridge needs based on the kinds of fields in its
   * plan. Container types are not among them: a container helper renders every type fully
   * qualified, so importing them would only reintroduce a simple-name collision. Returned set is
   * fed into {@link AbstractTelescopeProcessor#writeClass(String, String, Set, String, Element,
   * java.util.function.Consumer)} so the emitted file has clean imports instead of FQNs in the
   * body.
   */
  private Set<String> importsFor(final Map<String, FieldPlan> fieldPlans) {
    final var imports = new TreeSet<String>();
    for (final var plan : fieldPlans.values()) {
      switch (plan.kind()) {
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
    final Map<String, String> renames,
    final String bridgePkg
  ) {
    for (final var entry : fieldPlans.entrySet()) {
      final var fieldName = entry.getKey();
      final var plan = entry.getValue();
      final var srcType = fieldByName(sourceFields, fieldName).type();
      final var tgtType = fieldByName(targetFields, renames.getOrDefault(fieldName, fieldName)).type();
      // Identity elements are normally copied inline, except where copiesThroughHelper says the
      // copy constructor cannot build the output correctly.
      if (plan.rawContainer()) {
        emitRawContainerHelper(out, "__fwd_" + fieldName, srcType, tgtType, plan, "forward", bridgePkg);
        emitRawContainerHelper(out, "__bwd_" + fieldName, tgtType, srcType, plan, "backward", bridgePkg);
        continue;
      }
      if (copiesThroughHelper(plan, plan.fwdContainerImpl())) {
        emitRawContainerHelper(out, "__fwd_" + fieldName, srcType, tgtType, plan, "forward", bridgePkg);
      }
      if (copiesThroughHelper(plan, plan.bwdContainerImpl())) {
        emitRawContainerHelper(out, "__bwd_" + fieldName, tgtType, srcType, plan, "backward", bridgePkg);
      }
      if (IDENTITY_ELEMENT_SENTINEL.equals(plan.subBridgeName())) continue;
      switch (plan.kind()) {
        case LIST -> {
          emitListHelper(out, "__fwd_" + fieldName, srcType, tgtType, plan.subBridgeName(), "forward", bridgePkg);
          emitListHelper(out, "__bwd_" + fieldName, tgtType, srcType, plan.subBridgeName(), "backward", bridgePkg);
        }
        case SET -> {
          emitSetHelper(out, "__fwd_" + fieldName, srcType, tgtType, plan.subBridgeName(), "forward", bridgePkg);
          emitSetHelper(out, "__bwd_" + fieldName, tgtType, srcType, plan.subBridgeName(), "backward", bridgePkg);
        }
        case MAP_VALUES -> {
          emitMapHelper(out, "__fwd_" + fieldName, srcType, tgtType, plan.subBridgeName(), "forward", bridgePkg);
          emitMapHelper(out, "__bwd_" + fieldName, tgtType, srcType, plan.subBridgeName(), "backward", bridgePkg);
        }
        default -> {
        }
      }
    }
  }

  /**
   * Whether one direction of a container field is copied by its self-contained helper rather than
   * by the inline copy constructor, which is right only for a JDK class over elements that pass
   * through unchanged. {@code outputImpl} is the class that direction allocates.
   *
   * <p>A type the adopter wrote does not inherit the JDK copy constructor. A sorted output is the
   * other case: which constructor {@code new TreeSet<>(src)} binds is fixed by the declared type of
   * {@code src}, so a source written as a plain {@code Set} that holds a {@code SortedSet} is
   * copied through the {@code Collection} overload and loses its comparator. The helper reads the
   * comparator from the value. The question is asked per direction, because only an output that
   * keeps an order needs the helper, and the other direction keeps the copy constructor.
   */
  private boolean copiesThroughHelper(final FieldPlan plan, final String outputImpl) {
    if (plan.rawContainer()) return true;
    if (!IDENTITY_ELEMENT_SENTINEL.equals(plan.subBridgeName())) return false;
    if (plan.kind() != FieldPlan.Kind.SET && plan.kind() != FieldPlan.Kind.MAP_VALUES) return false;
    return outputImpl != null && keepsOrder(outputImpl, plan.kind());
  }

  // Emit one direction of a raw Collection/Map subtype container helper. Every type is rendered
  // fully-qualified (no imports needed). The output is filled by addAll/putAll for an identity
  // element, or by an element-bridging loop otherwise.
  private void emitRawContainerHelper(
    final PrintWriter out,
    final String name,
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final FieldPlan plan,
    final String direction,
    final String bridgePkg
  ) {
    final var identity = IDENTITY_ELEMENT_SENTINEL.equals(plan.subBridgeName());
    final var sub = plan.subBridgeName();
    out.println();
    // A generic class used raw is a raw type wherever it is named, and its elements reach the copy
    // as Object, so the signature and the fill are raw and unchecked by construction. The shared
    // spec copies such a pair only where nothing it holds can be of a type the other side refuses,
    // and the suppression keeps a consumer compiling with -Werror from failing on that.
    if (mentionsRawUse(srcContainer) || mentionsRawUse(tgtContainer)) out.println(RAW_SUPPRESSION);
    out.println(
      "  private static " + tgtContainer + " " + name + "(final " + srcContainer + " src" + pathParameter(sub) + ") {"
    );
    out.println("    if (src == null) return null;");
    emitOrderingPrelude(
      out,
      plan.kind(),
      identity,
      tgtContainer,
      concreteImplFqn(tgtContainer, plan.kind()),
      srcContainer
    );
    out.println(rawOutDeclaration(tgtContainer, plan.kind(), identity, bridgePkg));
    final var element = plan.kind() == FieldPlan.Kind.MAP_VALUES ? "e.getValue()" : "x";
    emitFill(out, tgtContainer, srcContainer, plan.kind(), identity ? null : subCall(sub, direction, element));
    out.println("    return out;");
    out.println("  }");
  }

  /**
   * Why the first of the two container types that nothing a rebuild can build is an instance of is
   * refused, or null when both have a class to build. That is asked of the shared allocation rules
   * before anything else, because whichever route a container takes it allocates a class that has
   * to be the declared type: an interface or abstract type the table does not name is built as its
   * family default, which has the right shape and need not be that type.
   */
  private String firstUnassignableContainer(
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final FieldPlan.Kind kind,
    final String bridgePkg
  ) {
    for (final var container : List.of(srcContainer, tgtContainer)) {
      // A declared type nothing allocatable is an instance of may still be reachable through its
      // own builder, which is the route the reflective path takes for exactly this shape. The test
      // is the allocator's own, so a gate never refuses a container the emitter would have built.
      if (builderAllocExpr(container, kind, bridgePkg) != null) continue;
      if (allocation.implementationFor(container, family(kind)) instanceof Allocation.Refuse refuse) {
        return refuse.reason();
      }
    }
    return null;
  }

  /**
   * The declared container's own qualified name when it can be reached through a static {@code
   * builder()}, or null when it cannot.
   *
   * <p>The reflective path binds this pair, so what has to hold is that the builder actually
   * produces the declared type. A {@code build()} returning something else fails its cast on the
   * first conversion, and emitting a call to it would move that failure into generated code.
   */
  private String builderRouteFor(final TypeMirror container, final String bridgePkg) {
    if (container.getKind() != TypeKind.DECLARED) return null;
    final var el = (TypeElement) ((DeclaredType) container).asElement();
    final var factory = staticBuilderMethod(el);
    if (factory == null || factory.getReturnType().getKind() != TypeKind.DECLARED) return null;
    final var builderEl = (TypeElement) ((DeclaredType) factory.getReturnType()).asElement();
    // The call lands in the bridge's package, which need not be the builder's, and a method on a
    // class that package cannot name is inaccessible however public the method itself is.
    if (!nameableFrom(builderEl, bridgePkg)) return null;
    final var types = processingEnv.getTypeUtils();
    // All members, not the declared ones: build() may be inherited from a shared builder base, and
    // it produces the same value wherever it is written.
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(builderEl))) {
      if (m.getModifiers().contains(Modifier.STATIC) || !m.getModifiers().contains(Modifier.PUBLIC)) continue;
      if (!m.getParameters().isEmpty() || !m.getSimpleName().contentEquals("build")) continue;
      if (types.isAssignable(types.erasure(m.getReturnType()), types.erasure(container))) {
        return el.getQualifiedName().toString();
      }
    }
    return null;
  }

  /**
   * The first of two containers held inside a field's type whose class the bridge's package cannot
   * name, or null when it can name both. The nested element class writes each container's type as a
   * parameter and a return type, so its erasure has to be nameable whatever the allocation. The
   * allocation then adds nothing to ask: it writes that same class, casts a builder's result to it,
   * or writes a public {@code java.base} default standing in for an interface. A field's own type
   * is asked before any plan is made, which is why this is reached only for the containers inside
   * it.
   */
  private String firstUnnameableContainer(
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final String bridgePkg
  ) {
    for (final var container : List.of(srcContainer, tgtContainer)) {
      final var hidden = unnameableErasure(container, bridgePkg);
      if (hidden != null) return hidden.getQualifiedName().toString();
    }
    return null;
  }

  private static String unnameableContainerMessage(
    final TypeElement source,
    final TypeElement target,
    final String fieldName,
    final String container,
    final String bridgePkg
  ) {
    return (
      "@Bridge " +
      source.getSimpleName() +
      " -> " +
      target.getSimpleName() +
      ": field '" +
      fieldName +
      "' container type '" +
      container +
      "' cannot be named from " +
      packagePhrase(bridgePkg) +
      ", where the bridge is generated — the helper converting it writes that type. Make it and" +
      " every class enclosing it public, or supply an explicit @ViaMapper for this field."
    );
  }

  /**
   * Why the first of the two containers the bridge cannot build is refused, or null when it can
   * build both. The shared allocation rules decide, for a rebuild emitted into {@code bridgePkg}:
   * the class they name has to have a no-argument constructor that package can call. A container
   * whose builder makes it needs no constructor of its own, which is the point of hiding one.
   */
  private String firstNonAllocatableContainer(
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final FieldPlan.Kind kind,
    final String bridgePkg
  ) {
    for (final var container : List.of(srcContainer, tgtContainer)) {
      if (builderAllocExpr(container, kind, bridgePkg) != null) continue;
      if (allocationOf(container, kind, bridgePkg) instanceof Allocation.Refuse refuse) return refuse.reason();
    }
    return null;
  }

  /**
   * The constructor argument that carries a sorted container's ordering across the rebuild, or
   * empty when the shared rules decide the rebuilt container takes none.
   */
  private String orderingArg(
    final FieldPlan.Kind kind,
    final TypeMirror tgtContainer,
    final String implFqn,
    final boolean elementsPreserved
  ) {
    if (!(ordering(kind, tgtContainer, implFqn, elementsPreserved) instanceof Ordering.Carry<TypeMirror>)) return "";
    // A container used raw names no type for the local to be declared over, so the local is a raw
    // comparator, which the constructor the shared rules found takes as it is.
    if (usedRaw(tgtContainer)) return ORDERING_LOCAL;
    // The argument and the local holding it are decided by the same questions, so a rebuild can
    // never name a local the line above it had no type to declare.
    return comparatorTypeArg(kind, tgtContainer) == null ? "" : ORDERING_LOCAL;
  }

  /**
   * How the rebuilt container is told the order its source kept, as the shared rules decide it for
   * the reflective path too.
   */
  private Ordering<TypeMirror> ordering(
    final FieldPlan.Kind kind,
    final TypeMirror tgtContainer,
    final String implFqn,
    final boolean elementsPreserved
  ) {
    final var implEl = processingEnv.getElementUtils().getTypeElement(implFqn);
    if (implEl == null) return new Ordering.None<>();
    final var family = switch (kind) {
      case SET -> ContainerView.Kind.SET;
      case MAP_VALUES -> ContainerView.Kind.MAP_VALUES;
      default -> ContainerView.Kind.LIST;
    };
    return rules.orderingFor(tgtContainer, implEl.asType(), family, elementsPreserved);
  }

  /**
   * The type a comparator over this container would order: a map's key type, a set's element type.
   *
   * <p>Read from the target's view as the plain container rather than from the implementation
   * class, because a generic implementation's own view answers with its type parameter and the
   * field is what fixed that parameter. A container used raw has none to read, and answers null.
   */
  private String comparatorTypeArg(final FieldPlan.Kind kind, final TypeMirror tgtContainer) {
    final var own = comparatorTypeMirror(kind, tgtContainer);
    return own == null ? null : own.toString();
  }

  /** The same type as a mirror, for the two questions asked of a constructor's parameter. */
  private TypeMirror comparatorTypeMirror(final FieldPlan.Kind kind, final TypeMirror tgtContainer) {
    final var iface = kind == FieldPlan.Kind.MAP_VALUES ? "java.util.Map" : "java.util.Set";
    final var args = containerViewArgs(tgtContainer, iface);
    return args.isEmpty() ? null : instantiable(args.getFirst());
  }

  /**
   * The local the comparator is read into, named once so the read and the two uses cannot drift
   * apart. A container's {@code comparator()} is not always a field read — a lazily initialised
   * sorted collection resolves on the call — so the null test and the constructor argument have to
   * be the same read rather than two.
   */
  private static final String ORDERING_LOCAL = "__cmp";

  /** Whether the class being allocated keeps its elements, or a map's keys, in an order. */
  private boolean keepsOrder(final String implFqn, final FieldPlan.Kind kind) {
    final var implEl = processingEnv.getElementUtils().getTypeElement(implFqn);
    if (implEl == null) return false;
    if (kind == FieldPlan.Kind.MAP_VALUES) return assignableToRaw(implEl.asType(), "java.util.SortedMap");
    return kind == FieldPlan.Kind.SET && assignableToRaw(implEl.asType(), "java.util.SortedSet");
  }

  /**
   * The check a rebuild has to make before it loses an order, or empty when it has none to make:
   * the refusal the shared rules decide, raised when the source turns out to be ordered by a
   * comparator. Natural ordering carries over untouched, which is why the test is on the comparator
   * rather than on sortedness.
   *
   * <p>The test is on the value rather than the declared type, because a field declared as a plain
   * {@code Set} can hold a sorted one. That is what the reflective path checks, and the two are
   * meant to accept the same programs.
   */
  private String orderingGuard(
    final FieldPlan.Kind kind,
    final boolean elementsPreserved,
    final TypeMirror tgtContainer,
    final String implFqn
  ) {
    if (!(ordering(kind, tgtContainer, implFqn, elementsPreserved) instanceof Ordering.Refuse<TypeMirror> refuse)) {
      return "";
    }
    final var family = kind == FieldPlan.Kind.MAP_VALUES ? "java.util.SortedMap<?, ?>" : "java.util.SortedSet<?>";
    return (
      "    if (src instanceof " +
      family +
      " __ordered && __ordered.comparator() != null) throw new IllegalStateException(\"" +
      escapeJavaString(refuse.reason()) +
      "\");"
    );
  }

  /**
   * Writes whichever line the ordering needs above the allocation: the refusal where the rebuilt
   * container keeps an order it cannot be told, the comparator read where it can be, and nothing
   * where it keeps none. Every rebuild whose output can keep an order emits through here, so the
   * choice is made once rather than once per call site.
   */
  private void emitOrderingPrelude(
    final PrintWriter out,
    final FieldPlan.Kind kind,
    final boolean elementsPreserved,
    final TypeMirror tgtContainer,
    final String implFqn,
    final TypeMirror srcContainer
  ) {
    final var guard = orderingGuard(kind, elementsPreserved, tgtContainer, implFqn);
    if (!guard.isEmpty()) {
      out.println(guard);
      return;
    }
    final var local = orderingLocal(kind, elementsPreserved, tgtContainer, implFqn, srcContainer);
    if (!local.isEmpty()) out.println(local);
  }

  /**
   * Reads the source's comparator into a local, once, for the allocation below to use twice.
   *
   * <p>The read is a pattern test rather than a call, because the declared source type need not
   * have a {@code comparator()} to call: what decides is the value, and a field written as a plain
   * {@code Map} can hold one that is ordered. The pattern names the type argument the declaration
   * already carries, so the comparator comes back typed for the constructor and nothing is cast.
   *
   * <p>Where either side is a generic class used raw, no pattern can name a type argument: a raw
   * source cannot be tested against a parameterized type, and a raw target has none to give. The
   * local is then a raw comparator, which any comparator constructor accepts.
   */
  private String orderingLocal(
    final FieldPlan.Kind kind,
    final boolean elementsPreserved,
    final TypeMirror tgtContainer,
    final String implFqn,
    final TypeMirror srcContainer
  ) {
    if (orderingArg(kind, tgtContainer, implFqn, elementsPreserved).isEmpty()) return "";
    final var family = kind == FieldPlan.Kind.MAP_VALUES ? "java.util.SortedMap<?, ?>" : "java.util.SortedSet<?>";
    if (usedRaw(tgtContainer) || usedRaw(srcContainer)) {
      // Raw, like a container it orders; the helper writing it suppresses the raw-type warning.
      return (
        "    final java.util.Comparator " +
        ORDERING_LOCAL +
        " = src instanceof " +
        family +
        " __ordered ? __ordered.comparator() : null;"
      );
    }
    final var typeArg = comparatorTypeArg(kind, tgtContainer);
    // Not a behavioural guard: a container with no readable arguments is refused while the pair is
    // paired, before anything asks about ordering. It is here so this answers null exactly where
    // orderingArg answers empty, which is what keeps the local and the argument in step.
    if (typeArg == null) return "";
    final var iface = kind == FieldPlan.Kind.MAP_VALUES ? "java.util.Map" : "java.util.Set";
    if (containerViewArgs(tgtContainer, iface).getFirst() instanceof WildcardType) {
      // The source's elements are a capture of the wildcard, so no pattern names their type and the
      // comparator comes back over that capture. The allocation orders the wildcard's bound, which
      // every captured element is, so the comparator is narrowed to it once, here.
      final var wide = "java.util.Comparator<? super " + typeArg + ">";
      return (
        "    @SuppressWarnings(\"unchecked\")\n    final " +
        wide +
        " " +
        ORDERING_LOCAL +
        " = src instanceof " +
        family +
        " __ordered ? (" +
        wide +
        ") __ordered.comparator() : null;"
      );
    }
    final var pattern =
      kind == FieldPlan.Kind.MAP_VALUES
        ? "java.util.SortedMap<" + typeArg + ", ?>"
        : "java.util.SortedSet<" + typeArg + ">";
    final var read = "src instanceof " + pattern + " __ordered ? __ordered.comparator() : null;";
    final var param = (
      (Ordering.Carry<TypeMirror>) ordering(kind, tgtContainer, implFqn, elementsPreserved)
    ).parameter();
    final var own = comparatorTypeMirror(kind, tgtContainer);
    if (own == null || acceptsWideComparator(param, own)) {
      return "    final java.util.Comparator<? super " + typeArg + "> " + ORDERING_LOCAL + " = " + read;
    }
    // A parameter naming the type exactly cannot hold a comparator over a supertype of it, so the
    // narrowing the constructor asked for is written out. It is sound for the reason the reflective
    // path is sound: the value came off a container ordered by that very type, and that path binds
    // this constructor by erasure and hands the comparator over with no check at all.
    return (
      "    @SuppressWarnings(\"unchecked\")\n    final " +
      param +
      " " +
      ORDERING_LOCAL +
      " = (" +
      param +
      ") (" +
      read.substring(0, read.length() - 1) +
      ");"
    );
  }

  /**
   * Whether a comparator over any supertype of the container's own type fits this parameter. The
   * read produces one of those, since the value is reached through a wildcarded pattern.
   */
  private boolean acceptsWideComparator(final TypeMirror param, final TypeMirror own) {
    final var types = processingEnv.getTypeUtils();
    final var comparator = processingEnv.getElementUtils().getTypeElement("java.util.Comparator");
    if (comparator == null) return true;
    return types.isAssignable(types.getDeclaredType(comparator, types.getWildcardType(null, own)), param);
  }

  /**
   * The allocation expression for a container reached through its own {@code builder()}, or null
   * when there is no such route.
   *
   * <p>One shape covers every builder. A {@code builder()} that takes type parameters infers them
   * from the cast's target; one that does not returns whatever it returns and the cast narrows it.
   *
   * <p>The cast is unchecked by construction: a builder that hands back a wider type is exactly the
   * shape this route exists for. What keeps it honest is the probe, which admits a builder only
   * when its {@code build()} produces the declared type.
   */
  private String builderAllocExpr(final TypeMirror container, final FieldPlan.Kind kind, final String bridgePkg) {
    if (!needsBuilderRoute(container, kind, bridgePkg)) return null;
    // A container allocated by its builder carries whatever ordering build() chose, which nothing
    // here can pass a comparator to. A declared type promising an order would keep the promise only
    // by luck, so the route is not offered and the pairing is refused by name instead.
    if (promisesOrdering(container)) return null;
    final var route = builderRouteFor(container, bridgePkg);
    // Through Object, because two parameterizations of one type are unrelated: a build() declared
    // to return Buildable<Object> cannot be cast straight to Buildable<E>, and the raw type would
    // trade the unchecked warning for a rawtypes one.
    return route == null ? null : "(" + container + ") (Object) " + route + ".builder().build()";
  }

  /**
   * Whether the ordinary allocation is unavailable for this container, which is the only condition
   * under which its builder should be called.
   *
   * <p>A builder is a way to reach a type that cannot be constructed, not a better way to reach one
   * that can. A container the shared allocation rules can allocate is built and sized from the
   * source in one step; routing such a type through its builder instead drops that sizing and
   * adopts whatever {@code build()} chose to return, for a type whose allocation was never in
   * question.
   */
  private boolean needsBuilderRoute(final TypeMirror container, final FieldPlan.Kind kind, final String bridgePkg) {
    return (
      container.getKind() == TypeKind.DECLARED && allocationOf(container, kind, bridgePkg) instanceof Allocation.Refuse
    );
  }

  /**
   * Whether the declared container's type promises an iteration order, which is the contract a
   * comparator carries and an allocation that takes no comparator cannot reproduce.
   */
  private boolean promisesOrdering(final TypeMirror container) {
    final var types = processingEnv.getTypeUtils();
    for (final var fqn : List.of("java.util.SortedSet", "java.util.SortedMap")) {
      final var el = processingEnv.getElementUtils().getTypeElement(fqn);
      if (el != null && types.isAssignable(types.erasure(container), types.erasure(el.asType()))) return true;
    }
    return false;
  }

  /**
   * The declaration of a helper's output container: its own builder where the ordinary allocation
   * is unavailable, and the sized allocation otherwise. All three helpers fill their output the
   * same way, so the route is decided here once and the declaration comes back whole — the
   * suppression the builder cast needs can then never be attached to a different allocation than
   * the one it was decided for.
   */
  private String helperOutDeclaration(
    final TypeMirror tgtContainer,
    final FieldPlan.Kind kind,
    final boolean elementsPreserved,
    final String bridgePkg
  ) {
    final var viaBuilder = builderAllocExpr(tgtContainer, kind, bridgePkg);
    if (viaBuilder != null) return outDeclaration(viaBuilder, true);
    final var implFqn = concreteImplFqn(tgtContainer, kind);
    final var arguments = allocTypeArguments(tgtContainer, kind);
    if (arguments.stream().anyMatch(Objects::isNull)) {
      return rawTypedOutDeclaration(
        tgtContainer,
        sizedAlloc(tgtContainer, kind, implFqn, "", orderingArg(kind, tgtContainer, implFqn, elementsPreserved))
      );
    }
    final var typeArgs = typeArgumentClause(
      implFqn,
      arguments.stream().map(String::valueOf).collect(Collectors.joining(", "))
    );
    return outDeclaration(
      sizedAlloc(tgtContainer, kind, implFqn, typeArgs, orderingArg(kind, tgtContainer, implFqn, elementsPreserved)),
      false
    );
  }

  /**
   * The same decision for the self-contained helper, whose elements pass through rather than being
   * converted one at a time.
   */
  private String rawOutDeclaration(
    final TypeMirror container,
    final FieldPlan.Kind kind,
    final boolean elementsPreserved,
    final String bridgePkg
  ) {
    final var viaBuilder = builderAllocExpr(container, kind, bridgePkg);
    if (viaBuilder != null) return outDeclaration(viaBuilder, true);
    final var implFqn = concreteImplFqn(container, kind);
    if (
      container instanceof DeclaredType declared &&
      !declared.getTypeArguments().isEmpty() &&
      allocTypeArguments(container, kind).stream().anyMatch(Objects::isNull)
    ) {
      return rawTypedOutDeclaration(
        container,
        sizedAlloc(container, kind, implFqn, "", orderingArg(kind, container, implFqn, elementsPreserved))
      );
    }
    return outDeclaration(rawAllocExpr(container, kind, elementsPreserved), false);
  }

  /**
   * The declaration for an output whose type arguments cannot be written after {@code new}: a
   * wildcard standing for a type parameter whose bound names a type variable, as {@code T extends
   * Comparable<T>} does, or that has several bounds. The class is allocated raw into a local
   * declared as the field's own type, which every element the helper adds already fits, and the
   * unchecked conversion that takes is suppressed on that one declaration.
   */
  private static String rawTypedOutDeclaration(final TypeMirror declared, final String alloc) {
    return "    @SuppressWarnings({\"unchecked\", \"rawtypes\"}) final " + declared + " out = " + alloc + ";";
  }

  /**
   * The output container's declaration. Reaching a declared type through its builder means casting
   * what {@code build()} hands back, which is unchecked by construction: the probe admits a builder
   * only when its {@code build()} produces that type, and the compiler cannot see that check. The
   * suppression rides the declaration so a consumer compiling with {@code -Werror} is not failed by
   * code they did not write; every other route allocates its own type and needs none.
   */
  private String outDeclaration(final String alloc, final boolean viaBuilder) {
    final var suppression = viaBuilder ? "@SuppressWarnings(\"unchecked\") " : "";
    return "    " + suppression + "final var out = " + alloc + ";";
  }

  /**
   * The allocation expression for a raw-container output: the target's concrete class (the subtype
   * itself when instantiable, else the interface's default impl), with a diamond only when that
   * class is generic. A non-generic subtype ({@code class ImageUrls extends ArrayList<ImageUrl>})
   * takes no type arguments; the default impl for a generic interface field takes the field's
   * element args.
   *
   * <p>Only the JDK default impls are sized from the source. Java does not inherit constructors, so
   * a subtype declaring nothing but its implicit no-arg one has no sized constructor to call, and a
   * subtype that declares an {@code (int)} constructor gives the argument whatever meaning it chose
   * — a page number reads exactly like a capacity from here. Filling such a subtype through {@code
   * addAll} or {@code putAll} still lets the JDK size it in one step where those methods presize.
   */
  private String rawAllocExpr(final TypeMirror container, final FieldPlan.Kind kind, final boolean elementsPreserved) {
    final var implFqn = concreteImplFqn(container, kind);
    final var implEl = processingEnv.getElementUtils().getTypeElement(implFqn);
    final var generic = implEl != null && !implEl.getTypeParameters().isEmpty();
    if (!generic) {
      // A type that declares no parameters still keeps whatever order its declaration promises, so
      // it is allocated the same way, only without the arguments it has nowhere to put.
      final var ordering = orderingArg(kind, container, implFqn, elementsPreserved);
      return ordering.isEmpty()
        ? "new " + implFqn + "()"
        : ordering + " == null ? new " + implFqn + "() : new " + implFqn + "(" + ordering + ")";
    }
    final var args = allocTypeArguments(container, kind)
      .stream()
      .map(String::valueOf)
      .collect(Collectors.joining(", "));
    return sizedAlloc(
      container,
      kind,
      implFqn,
      "<" + args + ">",
      orderingArg(kind, container, implFqn, elementsPreserved)
    );
  }

  /**
   * The type arguments to write into an allocation, key first where there is one.
   *
   * <p>They are the arguments of the class being allocated. Where that class is the declared type
   * itself, they are the declared type's own, which for a subtype need not be its container view's:
   * {@code class Tagged<Tag, E> extends ArrayList<E>} takes two. Where it is a default
   * implementation standing in for an interface, they come from the container view, which the
   * shared spec answers for every container that reaches here.
   */
  private List<TypeMirror> allocTypeArguments(final TypeMirror container, final FieldPlan.Kind kind) {
    if (
      container instanceof DeclaredType declared &&
      ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(concreteImplFqn(container, kind))
    ) {
      final var arguments = declared.getTypeArguments();
      final var parameters = ((TypeElement) declared.asElement()).getTypeParameters();
      final var instantiated = new ArrayList<TypeMirror>();
      for (int i = 0; i < arguments.size(); i++) instantiated.add(instantiable(arguments.get(i), parameters.get(i)));
      return instantiated;
    }
    final var view = rules.containerViewOf(container);
    // An interface used raw names no element type to write, so its default implementation is
    // allocated with the diamond and holds whatever it is given.
    if (view == null) return List.of();
    return kind == FieldPlan.Kind.MAP_VALUES
      ? List.of(instantiable(view.keyType()), instantiable(view.elementType()))
      : List.of(instantiable(view.elementType()));
  }

  /**
   * A type argument that can be written after {@code new}, which a wildcard cannot. A wildcard is
   * replaced by its upper bound, or {@code Object} where it has none: a container of that type
   * accepts every element the wildcard admits, which is what filling it from the source needs, and
   * it is assignable to the wildcard-typed field it is returned as. A lower bound gives no element
   * type the source's elements are known to be, so it allocates over {@code Object} too.
   */
  private TypeMirror instantiable(final TypeMirror argument) {
    if (!(argument instanceof WildcardType wildcard)) return argument;
    final var upper = wildcard.getExtendsBound();
    return upper != null ? upper : processingEnv.getElementUtils().getTypeElement("java.lang.Object").asType();
  }

  /**
   * The same for an argument of a class's own parameter, which may declare a bound. A wildcard then
   * allocates over its upper bound where that fits the parameter's bound, and over the parameter's
   * bound where it gives none. Null where no type can be written: a parameter with several bounds,
   * one whose bound names a type variable, as {@code T extends Comparable<T>} does, or an upper
   * bound the parameter's bound does not admit.
   */
  private TypeMirror instantiable(final TypeMirror argument, final TypeParameterElement parameter) {
    if (!(argument instanceof WildcardType wildcard)) return argument;
    final var bounds = parameter.getBounds();
    final var bound = bounds.size() == 1 ? bounds.getFirst() : null;
    final var unbounded =
      bound instanceof DeclaredType declared &&
      ((TypeElement) declared.asElement()).getQualifiedName().contentEquals("java.lang.Object");
    if (unbounded) return instantiable(argument);
    if (bound == null || MirrorProps.typeVariableIn(bound)) return null;
    final var upper = wildcard.getExtendsBound();
    if (upper == null) return bound;
    // An upper bound outside the parameter's leaves no type that is both within the bound and
    // assignable to the field, so nothing can be written.
    return processingEnv.getTypeUtils().isAssignable(upper, bound) ? upper : null;
  }

  /**
   * A container helper's signature, taking and returning each side's container exactly as it was
   * declared.
   *
   * <p>The declared type and the container it is viewed as are two different things for a subtype:
   * {@code class Tagged<Tag, E> extends ArrayList<E>} is declared with two arguments and holds
   * elements of its second. Each element is converted through the view, which is where its type is
   * found, and the signature names the declared type, which is the type the field has.
   */
  private static String helperSignature(
    final String name,
    final TypeMirror src,
    final TypeMirror tgt,
    final String pathParameter
  ) {
    return (
      "  private static " +
      declaredContainer(tgt) +
      " " +
      name +
      "(final " +
      declaredContainer(src) +
      " src" +
      pathParameter +
      ") {"
    );
  }

  /** A container type as declared: its raw name and its own type arguments. */
  private static String declaredContainer(final TypeMirror container) {
    final var arguments = ownArguments(container);
    return containerRawFqn(container) + (arguments.isEmpty() ? "" : "<" + arguments + ">");
  }

  /** A container's own type arguments as written, comma-separated, or empty for a raw type. */
  private static String ownArguments(final TypeMirror container) {
    return ((DeclaredType) container).getTypeArguments()
      .stream()
      .map(String::valueOf)
      .collect(Collectors.joining(", "));
  }

  private void emitListHelper(
    final PrintWriter out,
    final String name,
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final String subBridge,
    final String direction,
    final String bridgePkg
  ) {
    out.println();
    if (
      mentionsRawUse(srcContainer) || mentionsRawUse(tgtContainer) || givesBridgedArguments(srcContainer)
    ) out.println(RAW_SUPPRESSION);
    out.println(helperSignature(name, srcContainer, tgtContainer, pathParameter(subBridge)));
    out.println("    if (src == null) return null;");
    out.println(helperOutDeclaration(tgtContainer, FieldPlan.Kind.LIST, false, bridgePkg));
    out.println("    for (final var x : src) out.add(" + subCall(subBridge, direction, "x") + ");");
    out.println("    return out;");
    out.println("  }");
  }

  private void emitSetHelper(
    final PrintWriter out,
    final String name,
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final String subBridge,
    final String direction,
    final String bridgePkg
  ) {
    out.println();
    if (
      mentionsRawUse(srcContainer) || mentionsRawUse(tgtContainer) || givesBridgedArguments(srcContainer)
    ) out.println(RAW_SUPPRESSION);
    out.println(helperSignature(name, srcContainer, tgtContainer, pathParameter(subBridge)));
    out.println("    if (src == null) return null;");
    emitOrderingPrelude(
      out,
      FieldPlan.Kind.SET,
      false,
      tgtContainer,
      concreteImplFqn(tgtContainer, FieldPlan.Kind.SET),
      srcContainer
    );
    out.println(helperOutDeclaration(tgtContainer, FieldPlan.Kind.SET, false, bridgePkg));
    emitFill(out, tgtContainer, srcContainer, FieldPlan.Kind.SET, subCall(subBridge, direction, "x"));
    out.println("    return out;");
    out.println("  }");
  }

  /**
   * Fills {@code out} from {@code src}. A collection's elements are each converted by {@code
   * convert}, an expression over {@code x}; a map's values by {@code convert} over {@code
   * e.getValue()}, its keys passing through; and either is copied unchanged when {@code convert} is
   * null. Every helper that fills a set or a map emits through here, so an output that keeps an
   * order is guarded on every route.
   *
   * <p>A sorted output whose elements, or a sorted map whose keys, are not ordered against their
   * own kind can fail on the insert: one that cannot be ordered there raises a cast from inside the
   * container. That cast is turned into the refusal the reflective path gives, word for word,
   * naming the container as declared and the offending element's or key's runtime class. Those
   * ordered against their own kind keep the plain fill, so a cast from inside their own {@code
   * compareTo} propagates as it is, as it does there.
   */
  private void emitFill(
    final PrintWriter out,
    final TypeMirror tgtContainer,
    final TypeMirror srcContainer,
    final FieldPlan.Kind kind,
    final String convert
  ) {
    final var map = kind == FieldPlan.Kind.MAP_VALUES;
    final var guarded =
      (kind == FieldPlan.Kind.SET || map) &&
      keepsOrder(concreteImplFqn(tgtContainer, kind), kind) &&
      !orderedAgainstItsOwnKind(comparatorTypeMirror(kind, tgtContainer));
    if (!guarded) {
      if (convert == null) {
        out.println(map ? "    out.putAll(src);" : "    out.addAll(src);");
      } else {
        out.println(
          map
            ? "    for (final var e : src.entrySet()) out.put(e.getKey(), " + convert + ");"
            : "    for (final var x : src) out.add(" + convert + ");"
        );
      }
      return;
    }
    final var family = map ? OrderedNouns.MAP : OrderedNouns.SET;
    if (convert != null) {
      emitOrderedInsert(out, tgtContainer, srcContainer, family, "    ", convert);
      return;
    }
    // addAll and putAll keep a sorted container's linear build from a source sorted the same way.
    // Once one has failed, the fill ends in a refusal whatever the source does next: the source is
    // inserted again one at a time, and the first element or key that cannot be ordered is named;
    // a source that does not yield such a one again is refused for the original cast, without a
    // name.
    out.println("    try {");
    out.println(map ? "      out.putAll(src);" : "      out.addAll(src);");
    out.println("    } catch (final ClassCastException __unordered) {");
    out.println("      out.clear();");
    emitOrderedInsert(out, tgtContainer, srcContainer, family, "      ", null);
    out.println("      throw new IllegalStateException(");
    out.println(
      "        " +
        literal(
          PairingMessages.unorderableInsertHead(binaryNameOf(tgtContainer), map) +
            PairingMessages.unorderableInsertUnnamed(map)
        )
    );
    emitRefusalAdvice(out, family, "        ", "__unordered");
    out.println("    }");
  }

  /** Which of the two sorted families an ordering refusal is about. */
  private enum OrderedNouns {
    SET,
    MAP;

    boolean map() {
      return this == MAP;
    }
  }

  /** The declared container's binary name, which is how the reflective path names it. */
  private String binaryNameOf(final TypeMirror container) {
    final var declared = (TypeElement) ((DeclaredType) container).asElement();
    return processingEnv.getElementUtils().getBinaryName(declared).toString();
  }

  /**
   * The end of an ordering refusal, from the advice through the closing parenthesis, with {@code
   * cause} as the cause. Every refusal of an unorderable element or key ends with these words.
   */
  private static void emitRefusalAdvice(
    final PrintWriter out,
    final OrderedNouns family,
    final String indent,
    final String cause
  ) {
    out.println(indent + "  + " + literal(PairingMessages.unorderableInsertAdvice(family.map())) + ",");
    out.println(indent + cause);
    out.println(indent.substring(2) + ");");
  }

  /** {@code text} as a Java string literal. */
  private static String literal(final String text) {
    return "\"" + escapeJavaString(text) + "\"";
  }

  /**
   * A loop inserting each element of {@code src}, or each entry of a map, into a sorted {@code
   * out}, turning a cast from the insert into the reflective path's refusal. {@code convert} is as
   * for {@link #emitFill}; a converted element or value is converted once into a local and the
   * local is inserted, so a conversion that counts or generates sees each one exactly once, as it
   * does in the unsorted loop.
   */
  private void emitOrderedInsert(
    final PrintWriter out,
    final TypeMirror tgtContainer,
    final TypeMirror srcContainer,
    final OrderedNouns family,
    final String indent,
    final String convert
  ) {
    final var outRaw = binaryNameOf(tgtContainer);
    final var map = family == OrderedNouns.MAP;
    final String ordered;
    if (map) {
      ordered = "__k";
      if (usedRaw(srcContainer)) {
        // A map used raw yields its entries as Object. Read through a view naming no types, which
        // a raw map is assignable to without a warning, they come back as entries.
        out.println(indent + "final java.util.Map<?, ?> __entries = src;");
        out.println(indent + "for (final var e : __entries.entrySet()) {");
      } else {
        out.println(indent + "for (final var e : src.entrySet()) {");
      }
      out.println(indent + "  final var __k = e.getKey();");
      out.println(indent + "  final var __v = " + (convert == null ? "e.getValue()" : convert) + ";");
    } else {
      ordered = convert == null ? "x" : "__e";
      out.println(indent + "for (final var x : src) {");
      if (convert != null) out.println(indent + "  final var __e = " + convert + ";");
    }
    out.println(indent + "  try {");
    out.println(indent + (map ? "    out.put(__k, __v);" : "    out.add(" + ordered + ");"));
    out.println(indent + "  } catch (final ClassCastException __cast) {");
    out.println(indent + "    throw new IllegalStateException(");
    out.println(indent + "      " + literal(PairingMessages.unorderableInsertHead(outRaw, map)));
    out.println(indent + "        + " + ordered + ".getClass().getName()");
    // Asked through the class object rather than with instanceof: a value of a final type that is
    // not Comparable makes instanceof a compile error, and casting to Object first is redundant
    // where the value is already Object, as it is in a copy between containers used raw.
    out.println(indent + "        + (Comparable.class.isInstance(" + ordered + ")");
    out.println(indent + "          ? " + literal(PairingMessages.unorderableInsertComparable(true)));
    out.println(indent + "          : " + literal(PairingMessages.unorderableInsertComparable(false)) + ")");
    emitRefusalAdvice(out, family, indent + "      ", "__cast");
    out.println(indent + "  }");
    out.println(indent + "}");
  }

  /**
   * Whether elements of this type declare themselves comparable with things of their own kind, the
   * reflective path's test for a cast escaping a sorted insert being the element's own fault rather
   * than an ordering problem. Such an element's cast is left to propagate, as it does there.
   *
   * <p>The walk is the reflective path's: the first parameterized {@code Comparable} among the
   * supertypes answers, a type variable in it is read through the arguments passed on the way up,
   * and a raw one is followed like any other type. It starts from the erasure of the declared
   * element type because the reflective path starts from a runtime class, which carries no type
   * arguments; so a variable the class itself leaves open answers false on both.
   *
   * <p>The reflective path asks each element's runtime class and this asks the declared element
   * class. The answers differ only for an element whose runtime class is a proper subclass of the
   * declared one, ordered against its own kind while the declared class is not: the subclass
   * declares {@code Comparable} itself, binds a variable the declared class leaves open, or is what
   * the declared class compares against. The generated code then refuses a cast the reflective path
   * lets through, with that cast as the cause. The other way round cannot happen: a subclass
   * inherits its superclass's declaration, and anything a class can be compared with, its
   * subclasses can be too. A final declared class, a record among them, has no such subclass.
   */
  private boolean orderedAgainstItsOwnKind(final TypeMirror element) {
    if (!(element instanceof DeclaredType)) return false;
    final var comparable = processingEnv.getElementUtils().getTypeElement("java.lang.Comparable");
    if (comparable == null) return false;
    final var types = processingEnv.getTypeUtils();
    final var own = types.erasure(element);
    final Deque<TypeMirror> pending = new ArrayDeque<>(List.of(own));
    while (!pending.isEmpty()) {
      final var type = pending.poll();
      if (type instanceof DeclaredType declared && declared.asElement().equals(comparable)) {
        if (declared.getTypeArguments().isEmpty()) continue;
        final var against = declared.getTypeArguments().getFirst();
        return (
          against instanceof DeclaredType target &&
          target.getTypeArguments().isEmpty() &&
          types.isAssignable(own, types.erasure(target))
        );
      }
      pending.addAll(types.directSupertypes(type));
    }
    return false;
  }

  private void emitMapHelper(
    final PrintWriter out,
    final String name,
    final TypeMirror srcContainer,
    final TypeMirror tgtContainer,
    final String subBridge,
    final String direction,
    final String bridgePkg
  ) {
    out.println();
    if (
      mentionsRawUse(srcContainer) || mentionsRawUse(tgtContainer) || givesBridgedArguments(srcContainer)
    ) out.println(RAW_SUPPRESSION);
    out.println(helperSignature(name, srcContainer, tgtContainer, pathParameter(subBridge)));
    out.println("    if (src == null) return null;");
    emitOrderingPrelude(
      out,
      FieldPlan.Kind.MAP_VALUES,
      true,
      tgtContainer,
      concreteImplFqn(tgtContainer, FieldPlan.Kind.MAP_VALUES),
      srcContainer
    );
    out.println(helperOutDeclaration(tgtContainer, FieldPlan.Kind.MAP_VALUES, true, bridgePkg));
    emitFill(out, tgtContainer, srcContainer, FieldPlan.Kind.MAP_VALUES, subCall(subBridge, direction, "e.getValue()"));
    out.println("    return out;");
    out.println("  }");
  }

  /**
   * The first of the two sides that is an abstract class, or {@code null} when both can be
   * constructed. A rebuild calls a constructor and an abstract class has none to call, so the pair
   * is refused here rather than emitted and left for javac to reject inside a file the author never
   * wrote. An interface is handled separately; this is the class case the kind check lets through.
   */
  private static TypeElement firstAbstractSide(final TypeElement source, final TypeElement target) {
    for (final var el : List.of(source, target)) {
      if (el.getKind() == ElementKind.CLASS && el.getModifiers().contains(Modifier.ABSTRACT)) return el;
    }
    return null;
  }

  /** Where a pair's generated bridge is emitted, and the simple name it is emitted under. */
  private record BridgeName(String pkg, String simple) {
    String qualified() {
      return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    /** The name as written in a file in {@code fromPkg}: simple there, qualified anywhere else. */
    String referencedFrom(final String fromPkg) {
      return pkg.isEmpty() || pkg.equals(fromPkg) ? simple : qualified();
    }
  }

  /**
   * The configuration a {@code @Bridge} declares for {@code pair}, held back or not, or null when
   * no {@code @Bridge} names it. See {@link #bridgeNameOf}.
   */
  private BridgeConfig configOf(final TypePair pair) {
    return configsByPair.containsKey(pair) ? configsByPair.get(pair) : deferredConfigs.get(pair);
  }

  /**
   * Whether a {@code @Bridge} names {@code pair}, which its bridge says through {@code
   * declaresPair()} so a runtime mapper can tell it from a bridge derived under the same long name.
   */
  private boolean declares(final TypePair pair) {
    return configOf(pair) != null;
  }

  /**
   * The name and package of a pair's bridge. This is the only place either is decided: the bridge
   * is written under it and every other bridge refers to it through it, so a reference cannot name
   * a class nothing writes.
   *
   * <ul>
   *   <li>A pair a carrier declares is {@code <Carrier>Bridge}, beside the carrier.
   *   <li>A pair its source declares is {@code <Source>Bridge}, beside the source, unless the
   *       source declares more than one target: the short name can belong to only one pair, so then
   *       every one of them takes the long name.
   *   <li>Any other pair, one reached only as a field or element of another, is {@code
   *       <Source>To<Target>Bridge}, beside the source, so it never takes a short name a declared
   *       pair could own.
   * </ul>
   *
   * <p>Whether a pair is declared is read from the configurations parsed off every {@code @Bridge},
   * including those held back for the final round because Lombok has not finished with a side. It
   * is not read from the drain that reaches the pair: one pair can be reached from more than one
   * drain, and from a round other than the one its annotation was read in.
   */
  private BridgeName bridgeNameOf(final TypeElement source, final TypeElement target) {
    final var elements = processingEnv.getElementUtils();
    final var sourceFq = source.getQualifiedName().toString();
    final var pair = new TypePair(sourceFq, target.getQualifiedName().toString());
    final var cfg = configOf(pair);
    final var carrierEl = cfg == null || cfg.carrierFq() == null ? null : elements.getTypeElement(cfg.carrierFq());
    if (carrierEl != null) {
      return new BridgeName(
        elements.getPackageOf(carrierEl).getQualifiedName().toString(),
        carrierEl.getSimpleName() + "Bridge"
      );
    }
    final var pkg = elements.getPackageOf(source).getQualifiedName().toString();
    if (cfg != null && !multiTargetSources.contains(sourceFq)) {
      return new BridgeName(pkg, source.getSimpleName() + "Bridge");
    }
    return new BridgeName(pkg, source.getSimpleName() + "To" + target.getSimpleName() + "Bridge");
  }

  /**
   * Claims {@code name} for the pair, returning whether the pair's bridge should be written now. It
   * should not when the pair already holds the name, because its bridge has been written: the final
   * round's drain starts with nothing seen, so it can reach a pair an earlier drain wrote. It
   * should not either when another pair holds the name. Auto-derived names are built from the two
   * simple names, so two pairs emitting into one package from same-simple-named types land on one
   * name, and the clash is reported against the pair that caused it rather than left to the Filer.
   */
  private boolean claimBridgeName(final BridgeName name, final TypeElement source, final TypeElement target) {
    final var thisPair = new TypePair(source.getQualifiedName().toString(), target.getQualifiedName().toString());
    final var nameOwner = bridgeNameOwner.putIfAbsent(name.qualified(), thisPair);
    if (nameOwner == null) return true;
    if (nameOwner.equals(thisPair)) return false;
    error(
      source,
      "@Bridge: the generated bridge name " +
        name.qualified() +
        " is claimed by two different type pairs — " +
        nameOwner.sourceFq() +
        " -> " +
        nameOwner.targetFq() +
        " and " +
        thisPair.sourceFq() +
        " -> " +
        thisPair.targetFq() +
        ". Auto-derived names use the simple names, so types sharing a simple name across" +
        " packages collide. Declare an explicit @Bridge on one of the pairs, or rename a" +
        " type."
    );
    return false;
  }

  /**
   * The name a parent bridge in {@code parentPkg} calls a sub-bridge by. Auto-derived names are
   * built from the two simple names, so two sub-pairs whose types share simple names across
   * packages derive the same one, and a parent referencing both by simple name cannot resolve
   * either. Qualifying every sub-bridge outside the parent's own package removes the ambiguity at
   * the reference rather than at the name: the emitted expression names exactly one class, and the
   * file needs no import for it. No generated file imports a generated bridge.
   */
  private String subBridgeReference(final TypeElement subSource, final TypeElement subTarget, final String parentPkg) {
    return bridgeNameOf(subSource, subTarget).referencedFrom(parentPkg);
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
   * Write the marker that tells a compilation with the carrier only on its class path that the pair
   * of {@code source} and {@code target} has a bridge of its own ({@link CarrierMarkers}). It holds
   * the bridge's name, which nothing reads; the marker's presence is the answer.
   */
  private void emitCarrierMarker(
    final TypeElement source,
    final TypeElement target,
    final TypeElement carrier,
    final String qualifiedBridge
  ) {
    final var elements = processingEnv.getElementUtils();
    final var name = CarrierMarkers.relativeName(
      elements.getBinaryName(source).toString(),
      elements.getBinaryName(target).toString()
    );
    try {
      final var file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", name, carrier);
      try (final var out = new PrintWriter(file.openWriter())) {
        out.println(qualifiedBridge);
      }
    } catch (final IOException e) {
      error(carrier, "Failed to write " + name + ": " + e.getMessage());
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
    return MirrorProps.sameType(processingEnv.getTypeUtils(), a, b);
  }

  /** The erased type's qualified name, as a class literal in generated source names it. */
  private String rawName(final TypeMirror type) {
    return processingEnv.getTypeUtils().erasure(type).toString();
  }

  /**
   * The runtime's shallow copy of a same-typed container read once into {@code local}. It is given
   * the declared type, which is what it rebuilds a container whose own class cannot be built as.
   */
  private static String containerCopy(final String local, final FieldPlan plan) {
    return CONTAINER_COPY + ".of(" + local + ", " + plan.subBridgeName() + ".class)";
  }

  /** Named in full, so a type the user declares with the same simple name cannot shadow it. */
  private static final String CONTAINER_COPY = "io.github.eschizoid.telescope.conversion.ContainerCopy";

  /** Named in full, so a type the user declares with the same simple name cannot shadow it. */
  private static final String FIXED_CLASS = "io.github.eschizoid.telescope.conversion.FixedClass";

  /**
   * Whether a value of {@code type}, paired with the same type, is handed across as itself. The
   * shared rules decide, so a container the reflective path copies is copied here too.
   */
  private boolean passesThrough(final TypeMirror type, final String componentName) {
    return rules.decidePair(type, type, componentName) instanceof PairDecision.Identity;
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

  /**
   * Whether the shared decision pairs the two types case by case, as two sealed interfaces: their
   * bridge is the sealed one, dispatching each value to its case's own bridge.
   */
  private boolean dispatchesCases(final DeclaredType source, final DeclaredType target, final String name) {
    return rules.decidePair(source, target, name) instanceof PairDecision.DispatchCases;
  }

  /**
   * The shared rules' refusal of two declared types at least one of which is given type arguments,
   * or null for a pair the rules convert and for one given none. A sub-bridge names both classes
   * raw, so it converts such a pair whatever arguments it is given: the rules decide which
   * arguments two sealed interfaces, records or beans may be given, and refuse the rest.
   */
  private String parameterizedRefusal(final TypeMirror source, final TypeMirror target, final String name) {
    final var props = mirrorProps();
    if (props.typeArguments(source).isEmpty() && props.typeArguments(target).isEmpty()) return null;
    return rules.decidePair(source, target, name) instanceof PairDecision.Incompatible<TypeMirror> refused
      ? refused.message()
      : null;
  }

  private static boolean sealedInterface(final DeclaredType type) {
    final var element = type.asElement();
    return element.getKind() == ElementKind.INTERFACE && element.getModifiers().contains(Modifier.SEALED);
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
    // misleading "no setter for 'empty'" error. Same-kind container pairs are planned by the
    // container branch before reaching here; this exclusion is the backstop for the pairs that
    // branch can't claim (a kind mismatch like List-subtype vs Set-subtype, a Collection/Map
    // subtype opposite a non-container, or a container the shared spec gives no view), so they
    // fall to the accurate "no auto-bridge could be derived" diagnostic instead of the
    // bean-introspection crash.
    return !assignableToRaw(dt, "java.util.Collection") && !assignableToRaw(dt, "java.util.Map");
  }

  /**
   * The types a bridge can descend into from {@code type}, by qualified name: the record and class
   * types its properties mention, directly or as a type argument at any depth, and the same again
   * from each of those. A type in its own set can reach itself, so a graph of its instances can
   * lead back to an object that is still being converted.
   *
   * <p>The walk reads the declared instance fields as well as the properties a bridge pairs. Lombok
   * adds accessors for fields that are already declared, so the answer is the same before and after
   * its patches have run. The set may name a type no bridge descends into, which only guards a
   * bridge whose guard can never fire; every type a bridge does descend into is in it.
   */
  private Set<String> reachableFrom(final TypeElement type) {
    final var name = type.getQualifiedName().toString();
    final var cached = reachable.get(name);
    if (cached != null) return cached;
    final var found = new HashSet<String>();
    final Deque<TypeElement> pending = new ArrayDeque<>(List.of(type));
    while (!pending.isEmpty()) {
      for (final var next : mentionedTypes(pending.poll())) {
        if (found.add(next.getQualifiedName().toString())) pending.add(next);
      }
    }
    reachable.put(name, found);
    return found;
  }

  /**
   * The record and class types {@code type}'s properties and instance fields mention, and the
   * sealed interfaces, each of which mentions its cases.
   */
  private List<TypeElement> mentionedTypes(final TypeElement type) {
    // A sealed interface holds one of its cases, and has no properties of its own.
    if (type.getKind() == ElementKind.INTERFACE && type.getModifiers().contains(Modifier.SEALED)) {
      final var cases = new ArrayList<TypeElement>();
      for (final var permitted : type.getPermittedSubclasses()) {
        if (permitted instanceof DeclaredType declared) cases.add((TypeElement) declared.asElement());
      }
      return cases;
    }
    final Deque<TypeMirror> pending = new ArrayDeque<>();
    for (final var field : fieldsOf(type)) pending.add(field.type());
    for (var owner = type; owner != null; owner = userSuperclass(owner)) {
      for (final var field : ElementFilter.fieldsIn(owner.getEnclosedElements())) {
        if (!field.getModifiers().contains(Modifier.STATIC)) pending.add(field.asType());
      }
    }
    final var walked = new HashSet<String>();
    final var mentioned = new ArrayList<TypeElement>();
    while (!pending.isEmpty()) {
      final var mirror = pending.poll();
      if (!walked.add(mirror.toString())) continue;
      if (mirror instanceof ArrayType array) {
        pending.add(array.getComponentType());
      } else if (mirror instanceof WildcardType wildcard) {
        if (wildcard.getExtendsBound() != null) pending.add(wildcard.getExtendsBound());
        if (wildcard.getSuperBound() != null) pending.add(wildcard.getSuperBound());
      } else if (mirror instanceof DeclaredType declared) {
        pending.addAll(declared.getTypeArguments());
        if (isReflectableDeclared(declared) || sealedInterface(declared)) {
          mentioned.add((TypeElement) declared.asElement());
        } else if (!isJdkType(declared)) {
          // A container the adopter wrote, such as a class extending ArrayList<Item>, carries its
          // element type in a supertype's arguments.
          pending.addAll(processingEnv.getTypeUtils().directSupertypes(declared));
        }
      }
    }
    return mentioned;
  }

  /** The superclass of {@code type} when it is one the adopter wrote, or {@code null}. */
  private TypeElement userSuperclass(final TypeElement type) {
    if (!(type.getSuperclass() instanceof DeclaredType declared) || isJdkType(declared)) return null;
    return (TypeElement) declared.asElement();
  }

  private static boolean isJdkType(final DeclaredType type) {
    final var name = ((TypeElement) type.asElement()).getQualifiedName().toString();
    return name.startsWith("java.") || name.startsWith("javax.");
  }

  /**
   * Whether the bridge for {@code source} to {@code target} can be handed an object it is already
   * converting, which needs both sides to reach themselves. A bridge that cannot is emitted with no
   * cycle guard at all.
   */
  private boolean guardsCycles(final TypeElement source, final TypeElement target) {
    return (
      reachableFrom(source).contains(source.getQualifiedName().toString()) &&
      reachableFrom(target).contains(target.getQualifiedName().toString())
    );
  }

  /**
   * The properties of each side a generated bridge does not walk, in name order: on the source,
   * those it drops, transforms or converts through a {@code @ViaMapper}; on the target, those it
   * fills with a constant or a computed value, and those a transformed or {@code @ViaMapper}
   * property fills, which carry that property's name because the bridge refuses a converted
   * property that is also renamed.
   */
  record Unwalked(Set<String> source, Set<String> target) {
    static Unwalked of(
      final Collection<String> drops,
      final Collection<String> transformed,
      final Collection<String> viaMapped,
      final Collection<String> constants,
      final Collection<String> computes
    ) {
      final var source = new TreeSet<String>(drops);
      source.addAll(transformed);
      source.addAll(viaMapped);
      final var target = new TreeSet<String>(constants);
      target.addAll(computes);
      target.addAll(transformed);
      target.addAll(viaMapped);
      return new Unwalked(source, target);
    }
  }

  /**
   * Whether the bridge this processor generates for the pair of {@code source} and {@code target}
   * takes the active path of a runtime mapper converting the pair on a cycle, which it does where
   * it guards against an object reaching itself ({@link #guardsCycles}).
   */
  boolean takesActivePath(final TypeElement source, final TypeElement target) {
    return guardsCycles(source, target);
  }

  /** Whether {@code a} and {@code b} each reach the other, so they lie on one cycle. */
  private boolean onOneCycle(final TypeElement a, final TypeElement b) {
    return (
      reachableFrom(a).contains(b.getQualifiedName().toString()) &&
      reachableFrom(b).contains(a.getQualifiedName().toString())
    );
  }

  /**
   * Records that the bridge being planned passes its active path to {@code subBridgeName}, when the
   * sub-pair lies on a cycle with the pair being planned. A sub-pair off every cycle the parent is
   * on cannot lead back to it, so it starts a path of its own through its public method, which
   * converts the same way.
   */
  private void threadPathTo(
    final TypeElement parentSource,
    final TypeElement parentTarget,
    final TypeElement subSource,
    final TypeElement subTarget,
    final String subBridgeName
  ) {
    if (
      guardsCycles(parentSource, parentTarget) &&
      onOneCycle(parentSource, subSource) &&
      onOneCycle(parentTarget, subTarget)
    ) {
      threadedRefs.add(subBridgeName);
    }
  }

  /** The generated type of the active path: one identity set per bridge direction. */
  private static final String PATH_TYPE =
    "java.util.IdentityHashMap<Object, java.util.IdentityHashMap<Object, Boolean>>";

  /** An empty active path, which starts a conversion of its own. */
  private static final String FRESH_PATH = "new java.util.IdentityHashMap<>()";

  /**
   * One direction of a bridge, null in giving null out. {@code pathAccess} is {@code null} for a
   * bridge that cannot be handed an object it is already converting, which gets one plain method.
   *
   * <p>Any other bridge gets a public method that starts an empty path and an overload, of access
   * {@code pathAccess}, that carries it: an object already on the path converts to null, which
   * severs the cycle at the reference that closes it, as the runtime mapper does. The path holds
   * only the objects being converted right now, one identity set per bridge direction, so an object
   * reached twice along separate branches converts twice.
   */
  private static void emitDirection(
    final PrintWriter out,
    final String suppressRaw,
    final String pathAccess,
    final String direction,
    final String inFq,
    final String outFq,
    final String in,
    final String body
  ) {
    if (pathAccess == null) {
      out.print(suppressRaw);
      out.println("  public static " + outFq + " " + direction + "(final " + inFq + " " + in + ") {");
      out.println("    if (" + in + " == null) return null;");
      emitMethodBody(out, body);
      out.println("  }");
      out.println();
      return;
    }
    final var token = "__" + direction.toUpperCase(Locale.ROOT) + "_PATH";
    // The token names the pair this direction converts between, so a runtime mapper that hands
    // the bridge its own path finds the objects the bridge enters under that pair; on a path
    // the bridge started itself, the token is only an identity, as a plain object would be.
    out.println(
      "  private static final Object " +
        token +
        " = new io.github.eschizoid.telescope.PathKey(" +
        inFq +
        ".class, " +
        outFq +
        ".class);"
    );
    out.println();
    out.print(suppressRaw);
    out.println("  public static " + outFq + " " + direction + "(final " + inFq + " " + in + ") {");
    out.println("    return " + in + " == null ? null : " + direction + "(" + in + ", " + FRESH_PATH + ");");
    out.println("  }");
    out.println();
    out.print(suppressRaw);
    out.println(
      "  " +
        pathAccess +
        "static " +
        outFq +
        " " +
        direction +
        "(final " +
        inFq +
        " " +
        in +
        ", final " +
        PATH_TYPE +
        " __path) {"
    );
    out.println("    if (" + in + " == null) return null;");
    out.println("    final var __active = __path.computeIfAbsent(" + token + ", __k -> " + FRESH_PATH + ");");
    out.println("    if (__active.putIfAbsent(" + in + ", Boolean.TRUE) != null) return null;");
    out.println("    try {");
    final var inner = new StringWriter();
    try (var innerOut = new PrintWriter(inner)) {
      emitMethodBody(innerOut, body);
    }
    inner
      .toString()
      .lines()
      .forEach(line -> out.println("  " + line));
    out.println("    } finally {");
    out.println("      __active.remove(" + in + ");");
    out.println("    }");
    out.println("  }");
    out.println();
  }

  /**
   * The access of a guarded bridge's path-carrying overloads: package-private when every bridge
   * that can call them shares this bridge's package, public otherwise. Those callers are the
   * bridges whose source lies on a cycle with this one's source, each emitted beside its source or
   * beside its carrier.
   */
  private String pathOverloadAccess(final TypeElement source, final String pkg) {
    final var elements = processingEnv.getElementUtils();
    for (final var name : reachableFrom(source)) {
      final var type = elements.getTypeElement(name);
      if (type == null || !onOneCycle(source, type)) continue;
      if (!elements.getPackageOf(type).getQualifiedName().contentEquals(pkg)) return "public ";
    }
    final var configs = new HashMap<TypePair, BridgeConfig>(deferredConfigs);
    configs.putAll(configsByPair);
    for (final var entry : configs.entrySet()) {
      final var carrier = entry.getValue().carrierFq();
      final var other = elements.getTypeElement(entry.getKey().sourceFq());
      if (carrier == null || other == null || !onOneCycle(source, other)) continue;
      final var carrierType = elements.getTypeElement(carrier);
      if (carrierType == null || !elements.getPackageOf(carrierType).getQualifiedName().contentEquals(pkg)) {
        return "public ";
      }
    }
    return "";
  }

  /** Whether a call to {@code ref} from the code being emitted passes the active path. */
  private boolean threads(final String ref) {
    return pathArg != null && threadedRefs.contains(ref);
  }

  /**
   * A call to {@code ref}'s {@code direction} on {@code arg}, passing the active path if it takes
   * one.
   */
  private String subCall(final String ref, final String direction, final String arg) {
    return ref + "." + direction + "(" + arg + (threads(ref) ? ", " + pathArg : "") + ")";
  }

  /** {@code ref}'s {@code direction} as a function, passing the active path if it takes one. */
  private String subFunction(final String ref, final String direction) {
    return threads(ref) ? "__el -> " + subCall(ref, direction, "__el") : ref + "::" + direction;
  }

  /** The extra parameter of a helper whose element conversion takes the active path. */
  private String pathParameter(final String ref) {
    return threads(ref) ? ", final " + PATH_TYPE + " __path" : "";
  }

  // Read field `f` from `var`: `var.f()` for a record, `var.getF()` / `var.isF()` for a POJO.
  private String readExpr(final TypeElement owner, final String var, final Field f) {
    if (owner.getKind() == ElementKind.RECORD) return var + "." + f.name() + "()";
    return var + "." + getterName(owner, f.name(), f.type()) + "()";
  }

  /**
   * Which rung the rebuild of a target will use, with whatever member the choice turned on.
   *
   * <p>The emitted rebuild and the check that a transform fits have to agree about this. A field is
   * read through its getter and written through whichever member the rebuild picks, and those are
   * different types often enough to matter: a setter may be declared wider than the getter, and a
   * builder method may be declared narrower. Deciding it twice lets the check judge a row against a
   * member the emission never writes through, which either refuses a row that would have compiled
   * or accepts one that will not.
   */
  private record Rebuild(Kind kind, ExecutableElement constructor, TypeElement builderType) {
    private enum Kind {
      RECORD,
      CONSTRUCTOR,
      BUILDER,
      SETTERS,
      NONE,
    }
  }

  /**
   * Runs the same ladder the rebuild runs, and stops where it would stop. Consulted before the
   * emission so the check can ask what the emission will do rather than model it.
   *
   * <p>AUTO takes the first strategy in {@link BeanWriteStrategy#AUTO_ORDER} the target offers,
   * which is the order the runtime writer takes, so a bean offering several ways to be built is
   * built the same way by the bridge and by {@code Telescope.mapper}. A forced strategy is tried
   * alone.
   *
   * <p>A constructor counts when the bridge, emitted into {@code bridgePackage}, can call it: a
   * public one, or a package-private or protected one declared in that package. The runtime writer
   * calls any no-arg constructor, and a forced constructor of any access, through a private lookup;
   * a private one is the part of that the generated code cannot reach.
   */
  private Rebuild rebuildFor(
    final TypeElement to,
    final List<Field> toFields,
    final String writeStrategy,
    final PackageElement bridgePackage
  ) {
    if (to.getKind() == ElementKind.RECORD) return new Rebuild(Rebuild.Kind.RECORD, null, null);
    final var auto = "AUTO".equals(writeStrategy);
    final var names = toFields.stream().map(Field::name).toList();
    final var constructor = auto ? nameMatchedConstructor(to, names) : namedConstructor(to, toFields, bridgePackage);
    final var hasNoArgConstructor = hasNoArgConstructorFrom(to, bridgePackage);
    final var builder = staticBuilderMethod(to);
    final var builderType =
      builder != null && builder.getReturnType().getKind() == TypeKind.DECLARED
        ? (TypeElement) ((DeclaredType) builder.getReturnType()).asElement()
        : null;
    final var strategy = switch (writeStrategy) {
      case "CONSTRUCTOR" -> constructor != null ? BeanWriteStrategy.CONSTRUCTOR : null;
      case "BUILDER" -> builderType != null ? BeanWriteStrategy.BUILDER : null;
      case "SETTERS" -> hasNoArgConstructor ? BeanWriteStrategy.SETTERS : null;
      default -> autoBeanStrategy(
        to,
        names,
        name -> toFields.get(names.indexOf(name)).type(),
        hasNoArgConstructor
      ).orElse(null);
    };
    if (strategy == null) return new Rebuild(Rebuild.Kind.NONE, null, null);
    return switch (strategy) {
      case BUILDER -> new Rebuild(Rebuild.Kind.BUILDER, null, builderType);
      case CONSTRUCTOR -> new Rebuild(Rebuild.Kind.CONSTRUCTOR, constructor, null);
      case SETTERS -> new Rebuild(Rebuild.Kind.SETTERS, null, null);
    };
  }

  /**
   * Whether {@code to} declares a no-arg constructor a bridge emitted into {@code bridgePackage}
   * can call.
   */
  private boolean hasNoArgConstructorFrom(final TypeElement to, final PackageElement bridgePackage) {
    for (final var ctor : ElementFilter.constructorsIn(to.getEnclosedElements())) {
      if (ctor.getParameters().isEmpty() && bindableFrom(ctor, bridgePackage)) return true;
    }
    return false;
  }

  /**
   * A constructor taking one parameter per field, every one named after a field, that a bridge
   * emitted into {@code bridgePackage} can call — the constructor a forced {@code CONSTRUCTOR}
   * strategy calls. AUTO asks {@link #nameMatchedConstructor} instead, which requires it to be
   * public and the only constructor of that arity, as the runtime writer does.
   */
  private ExecutableElement namedConstructor(
    final TypeElement to,
    final List<Field> toFields,
    final PackageElement bridgePackage
  ) {
    for (final var ctor : ElementFilter.constructorsIn(to.getEnclosedElements())) {
      if (!bindableFrom(ctor, bridgePackage) || ctor.getParameters().size() != toFields.size()) continue;
      var matched = true;
      for (final var p : ctor.getParameters()) {
        if (!hasField(toFields, p.getSimpleName().toString())) {
          matched = false;
          break;
        }
      }
      if (matched) return ctor;
    }
    return null;
  }

  /**
   * The type of the member a rebuild writes {@code field} through, or {@code null} when the ladder
   * reaches no rung or that rung has no member for it. A null answer leaves the caller with the
   * declared type, which is what it used before there was anything better to ask.
   */
  private TypeMirror writeSlotType(
    final TypeElement to,
    final Rebuild rebuild,
    final String field,
    final TypeMirror stored,
    final PackageElement callerPackage
  ) {
    return switch (rebuild.kind()) {
      case RECORD -> to
        .getRecordComponents()
        .stream()
        .filter(c -> c.getSimpleName().contentEquals(field))
        .<TypeMirror>map(Element::asType)
        .findFirst()
        .orElse(null);
      case CONSTRUCTOR -> rebuild
        .constructor()
        .getParameters()
        .stream()
        .filter(p -> p.getSimpleName().contentEquals(field))
        .<TypeMirror>map(Element::asType)
        .findFirst()
        .orElse(null);
      // The builder method is read as a member of the type builder() returns, so one a generic
      // builder declares with its variable takes the class builder() fixes the variable to.
      case BUILDER -> {
        final var builder = staticBuilderMethod(to);
        final var member = builderSetterMember(to, rebuild.builderType(), field);
        if (member == null || builder == null || !(builder.getReturnType() instanceof DeclaredType made)) {
          yield builderSetterParameter(to, rebuild.builderType(), field);
        }
        yield ((ExecutableType) processingEnv.getTypeUtils().asMemberOf(made, member)).getParameterTypes().getFirst();
      }
      // The emission writes the setter's name and lets Java bind the overload, so the slot is
      // whichever one it binds for the value being stored -- not the first of that name, which
      // makes the answer depend on declaration order while the emitted text does not.
      //
      // The two ways this can fail are different answers. Ambiguous means several bind equally, so
      // taking the first of that name would judge the row against whichever was written first --
      // the order-dependence this resolver was brought in to remove. There is no slot, and the
      // declared type stands.
      //
      // None means nothing binds at all, and there naming the setter is order-independent:
      // whichever
      // one it names, it does not take the value, so the answer is refuse either way. Handing that
      // back to the declared type gives up the refusal in the one case the member is what refuses.
      // A variable-arity setter the property's value spreads into takes one value as its
      // component, which is what the emitted call passes, so the component is the slot.
      //
      // The named setter is read as a member of `to`, so one a superclass declares takes the type
      // `to` fixes the superclass's variable to.
      case SETTERS -> {
        final var choice = setterChoice(to, field);
        if (choice.spreads()) yield choice.argument();
        yield switch (resolvedFn(to, "set" + capitalize(field), stored, callerPackage)) {
          case Resolution.Bound bound -> bound.member().getParameterTypes().getFirst();
          case Resolution.Ambiguous ignored -> null;
          case Resolution.None ignored -> choice.member() == null
            ? null
            : (
                (ExecutableType) processingEnv.getTypeUtils().asMemberOf((DeclaredType) to.asType(), choice.member())
              ).getParameterTypes().getFirst();
        };
      }
      case NONE -> null;
    };
  }

  /**
   * The expression that rebuilds {@code to}, emitted from the rung {@code rebuild} chose.
   *
   * <p>It reads {@code writeStrategy} only to say which rung was asked for when none applied — the
   * ladder itself, and records always taking their canonical constructor whatever the strategy, are
   * {@link #rebuildFor}'s.
   *
   * <p>The choice is made once, by {@link #rebuildFor}, and handed here rather than made again. A
   * transform is checked against the member this will write through, so a second run of the ladder
   * is a second answer that can differ from the one the check was given — and the row would then be
   * judged against a member nothing calls.
   */
  private String buildExpr(
    final TypeElement to,
    final Function<String, String> read,
    final List<Field> toFields,
    final String writeStrategy,
    final TypeElement annotationSite,
    final Rebuild rebuild,
    final String hoistInto,
    final Set<String> transformed,
    final Map<String, TypeMirror> transformGives,
    final Set<String> referenceValued
  ) {
    final var toFq = to.getQualifiedName().toString();
    switch (rebuild.kind()) {
      case RECORD -> {
        final var slots = new ArrayList<CtorSlot>();
        for (final var c : to.getRecordComponents()) {
          final var name = c.getSimpleName().toString();
          final var gives = transformed.contains(name) ? transformGives.get(name) : null;
          final var value = read.apply(name);
          final var held = heldAs(c.asType());
          final var checked = writtenResult(value, gives, held, to, name);
          slots.add(new CtorSlot(name, c.asType(), checked));
        }
        return construct(to, slots, hoistInto);
      }
      case CONSTRUCTOR -> {
        final var slots = new ArrayList<CtorSlot>();
        for (final var pa : rebuild.constructor().getParameters()) {
          final var name = pa.getSimpleName().toString();
          final var gives = transformed.contains(name) ? transformGives.get(name) : null;
          final var value = read.apply(name);
          final var held = heldAs(pa.asType());
          final var checked = writtenResult(value, gives, held, to, name);
          slots.add(new CtorSlot(name, pa.asType(), checked));
        }
        return construct(to, slots, hoistInto);
      }
      case BUILDER -> {
        final var builderType = rebuild.builderType();
        final var sb = new StringBuilder(toFq + ".builder()");
        for (final var f : toFields) {
          final var method = builderSetter(to, builderType, f.name());
          // A property no strategy writes (a computed getter, a final field given its value where
          // it is declared) is skipped, as the runtime builder writer skips it. One that only a
          // strategy can set is refused: building without it would drop the value.
          if (method == null && !storedField(to, f.name())) continue;
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
          final var gives = transformed.contains(f.name()) ? transformGives.get(f.name()) : null;
          final var argument =
            gives == null
              ? read.apply(f.name())
              : resultArgument(
                  read.apply(f.name()),
                  gives,
                  heldAs(writeSlotType(to, rebuild, f.name(), gives, null)),
                  to,
                  f.name()
                );
          sb.append(".").append(method).append("(").append(argument).append(")");
        }
        return sb.append(".build()").toString();
      }
      case SETTERS -> {
        final var sb = new StringBuilder("{ final var out = new " + toFq + "(); ");
        for (final var f : toFields) {
          // A value of the field's own type is written through the setter, which has to take it;
          // a transformed value only has to fit the member, which the transform check decided.
          final var refusal = setterRefusal(to, f.name(), transformed.contains(f.name()));
          if (refusal != null) {
            error(annotationSite, "@Bridge: " + refusal);
            return null;
          }
          final var setter = setterName(to, f.name());
          if (setter == null) {
            error(
              annotationSite,
              "@Bridge: " + toFq + " has a no-arg constructor but no setter for '" + f.name() + "'"
            );
            return null;
          }
          // Every value is written through the setter chosen from the field's own type, as the
          // runtime writer chooses it. Where that setter has overloads, or the value is a
          // transform's result, it is first assigned to a local of the chosen parameter's type,
          // which binds that method; a cast would type a conditional on its own and unbox a null.
          // A transform's reference result bound for a primitive parameter is skipped when null,
          // as the runtime writer skips a null there.
          //
          // A transform result of a type wider than the chosen reference parameter is cast to it
          // and written through that setter: a runtime row can be typed to return such a type,
          // and the runtime writes it through the setter chosen from the field's type all the
          // same. Any other result the chosen parameter cannot take is passed as it is, and javac
          // binds the overload that takes it; no runtime row can be typed to return it.
          final var choice = setterChoice(to, f.name());
          final var value = read.apply(f.name());
          final var split = patchSplits.get(f.name());
          if (split != null && choice.argument() != null) {
            sb.append(splitWrite(split, setter, choice, to, f.name()));
            continue;
          }
          final var gives = transformed.contains(f.name()) ? transformGives.get(f.name()) : null;
          final var types = processingEnv.getTypeUtils();
          if (
            gives != null &&
            !mirrorProps().mentionsTypeVariable(gives) &&
            choice.argument() != null &&
            types.isAssignable(gives, choice.argument())
          ) {
            final var call = "{ final " + boundArgumentText(to, choice) + " __arg = __tv; out." + setter + "(__arg); }";
            if (choice.argument().getKind().isPrimitive() && !gives.getKind().isPrimitive()) {
              sb.append("{ final ").append(gives).append(" __tv = ").append(value).append("; ");
              sb.append("if (__tv != null) ").append(call).append(" } ");
            } else {
              sb
                .append("{ final ")
                .append(gives)
                .append(" __tv = ")
                .append(value)
                .append("; ")
                .append(call)
                .append(" } ");
            }
          } else if (
            !transformed.contains(f.name()) &&
            choice.argument() != null &&
            choice.argument().getKind().isPrimitive() &&
            (!f.type().getKind().isPrimitive() || referenceValued.contains(f.name())) &&
            !mirrorProps().mentionsTypeVariable(f.type())
          ) {
            // A reference value bound for a primitive parameter is skipped when null, as the
            // runtime writer skips it, and otherwise unboxed into the chosen parameter's type.
            sb
              .append("{ final ")
              .append(boxedType(f.type()))
              .append(" __tv = ")
              .append(value)
              .append("; if (__tv != null) { final ")
              .append(choice.argumentText())
              .append(" __arg = __tv; out.")
              .append(setter)
              .append("(__arg); } } ");
          } else if (
            choice.argument() != null &&
            choice.argument().getKind().isPrimitive() &&
            (reachesByCheck(gives, choice.argument()) || inferredIntoPrimitive(gives, choice.argument()))
          ) {
            // A result wider than the primitive parameter's wrapper is skipped when null, as the
            // runtime writer skips it, and otherwise checked against the wrapper and unboxed. A
            // result typed by its method's own variable is held as Object, which no instantiation
            // of the variable fails to fit.
            final var wrapper = boxedErasure(choice.argument());
            sb
              .append("{ final ")
              .append(mirrorProps().mentionsTypeVariable(gives) ? "Object" : gives.toString())
              .append(" __tv = ")
              .append(value)
              .append("; if (__tv != null) { final ")
              .append(choice.argumentText())
              .append(" __arg = (")
              .append(wrapper)
              .append(") ")
              .append(FIXED_CLASS)
              .append(".result(__tv, ")
              .append(wrapper)
              .append(".class, ")
              .append(types.erasure(to.asType()))
              .append(".class, ")
              .append(literal(f.name()))
              .append("); out.")
              .append(setter)
              .append("(__arg); } } ");
          } else if (castsToChosen(gives, choice.argument())) {
            // The local's type is the one the call binds the chosen setter by. A parameter with
            // type arguments is reached through a cast javac cannot check, which only this
            // local's declaration suppresses.
            final var bound = boundArgument(to, choice);
            final var boundText = boundArgumentText(to, choice);
            final var narrows = !types.isAssignable(gives, bound);
            final var unchecked = narrows && bound instanceof DeclaredType d && !d.getTypeArguments().isEmpty();
            sb
              .append("{ final ")
              .append(gives)
              .append(" __tv = ")
              .append(value)
              .append("; { ")
              .append(unchecked ? "@SuppressWarnings(\"unchecked\") " : "")
              .append("final ")
              .append(boundText)
              .append(" __arg = ")
              .append(
                narrows
                  ? "(" +
                    boundText +
                    ") " +
                    FIXED_CLASS +
                    ".result(__tv, " +
                    types.erasure(bound) +
                    ".class, " +
                    types.erasure(to.asType()) +
                    ".class, " +
                    literal(f.name()) +
                    ")"
                  : "__tv"
              )
              .append("; out.")
              .append(setter)
              .append("(__arg); } } ");
          } else if (!transformed.contains(f.name()) && choice.argumentType() != null) {
            sb
              .append("{ final ")
              .append(boundArgumentText(to, choice))
              .append(" __arg = ")
              .append(value)
              .append("; out.")
              .append(setter)
              .append("(__arg); } ");
          } else {
            sb.append("out.").append(setter).append("(").append(value).append("); ");
          }
        }
        return sb.append("return out; }").toString();
      }
      case NONE -> {
        error(annotationSite, noStrategyMessage(annotationSite, toFq, writeStrategy));
        return null;
      }
    }
    // A switch statement over an enum is not checked for exhaustiveness, so a rung added later
    // would fall through here and return the value meaning "already reported" -- emitting nothing
    // and saying nothing.
    throw new IllegalStateException("no emission for rebuild rung " + rebuild.kind());
  }

  /**
   * The parameter of the member the type of {@code field} of {@code to} chooses to write it
   * through: the setter {@link #setterChoice} picks, as a single value is passed to it, for a
   * setters rebuild, and {@code slot}, the member {@link #writeSlotType} names, for any other.
   */
  private TypeMirror chosenParameter(
    final TypeElement to,
    final Rebuild rebuild,
    final String field,
    final TypeMirror slot
  ) {
    if (rebuild.kind() != Rebuild.Kind.SETTERS) return slot;
    final var choice = setterChoice(to, field);
    return choice.member() == null ? null : choice.argument();
  }

  /**
   * Whether a result typed by a type variable its method declares, {@code gives}, is written into a
   * primitive {@code parameter}. The call is then passed where {@code Object} is taken, so javac
   * infers its variables from its argument and their bounds alone, and is checked and unboxed as a
   * result of a wider type is, a null result giving the parameter's default.
   */
  private boolean inferredIntoPrimitive(final TypeMirror gives, final TypeMirror parameter) {
    return (
      gives != null &&
      parameter != null &&
      parameter.getKind().isPrimitive() &&
      !gives.getKind().isPrimitive() &&
      mirrorProps().mentionsTypeVariable(gives)
    );
  }

  /**
   * Whether a conversion result of type {@code gives} is written into the primitive {@code
   * parameter} through the checked unboxing a result of a wider type takes, so that a null result
   * gives the parameter's default: a result typed by its method's own variable ({@link
   * #inferredIntoPrimitive}), or one of the parameter's own wrapper class, which unboxing alone
   * would turn a null into a {@code NullPointerException} for.
   */
  private boolean unboxedThroughCheck(final TypeMirror gives, final TypeMirror parameter) {
    return inferredIntoPrimitive(gives, parameter) || wrapperIntoPrimitive(gives, parameter);
  }

  /**
   * Whether {@code gives} is the wrapper class of a primitive that is {@code parameter}, a
   * primitive, or widens to it, as {@code Short} widens to {@code int}: unboxing it alone turns a
   * null into a {@code NullPointerException}, and no value of it fails a check of its class.
   */
  private boolean wrapperIntoPrimitive(final TypeMirror gives, final TypeMirror parameter) {
    if (gives == null || parameter == null || !parameter.getKind().isPrimitive()) return false;
    if (!(gives instanceof DeclaredType) || mirrorProps().mentionsTypeVariable(gives)) return false;
    final var types = processingEnv.getTypeUtils();
    final PrimitiveType unboxed;
    try {
      unboxed = types.unboxedType(gives);
    } catch (final IllegalArgumentException notAWrapper) {
      return false;
    }
    return types.isAssignable(unboxed, parameter);
  }

  /**
   * {@code value}, a conversion result of type {@code gives}, as it is written into {@code
   * parameter} of {@code property} of {@code owner}: a wrapper into its primitive, or one it widens
   * to, through the bridge's {@code __orElse}, which reads it once and gives the default for a
   * null; a result only a check of its class can show to fit, through {@code FixedClass}; any other
   * as it is.
   */
  private String writtenResult(
    final String value,
    final TypeMirror gives,
    final TypeMirror parameter,
    final TypeElement owner,
    final String property
  ) {
    if (wrapperIntoPrimitive(gives, parameter)) {
      final var kind = processingEnv.getTypeUtils().unboxedType(gives).getKind();
      orElseKinds.add(kind);
      return erasingBridge + ".__orElse(" + value + ", " + primitiveDefaultLiteral(kind).orElseThrow() + ")";
    }
    if (reachesByCheck(gives, parameter) || inferredIntoPrimitive(gives, parameter)) {
      return checkedResult(value, parameter, owner, property);
    }
    return value;
  }

  /**
   * {@code patch}'s value of {@code property}, a primitive the converted {@code partial} of type
   * {@code gives} is unboxed into: {@code partial} where {@code guard} is not null, its null as the
   * property's default, and the base's own value, {@code base}, otherwise, read as the primitive it
   * is. A setter skips a null result, so its write is split into a statement of its own, and the
   * value returned is not used.
   */
  private String patchUnboxed(
    final String property,
    final String guard,
    final String partial,
    final String base,
    final TypeMirror gives,
    final TypeMirror type
  ) {
    if (patchSetters) {
      patchSplits.put(property, new PatchSplit(guard, partial, base, gives));
      return base;
    }
    return "(" + guard + " != null ? " + writtenResult(partial, gives, type, patchOwner, property) + " : " + base + ")";
  }

  /**
   * The statement writing {@code split} through {@code setter}, whose chosen parameter is a
   * primitive: the base's value where the partial slot is null, and otherwise the converted value,
   * checked against its class where it is typed by its method's own variable, unless it is null.
   */
  private String splitWrite(
    final PatchSplit split,
    final String setter,
    final MirrorSetters.SetterChoice choice,
    final TypeElement owner,
    final String property
  ) {
    final var parameter = choice.argument();
    final var inferred = mirrorProps().mentionsTypeVariable(split.gives());
    final var wrapper = boxedErasure(parameter);
    final var converted = inferred
      ? "(" +
        wrapper +
        ") " +
        FIXED_CLASS +
        ".result(__tv, " +
        wrapper +
        ".class, " +
        processingEnv.getTypeUtils().erasure(owner.asType()) +
        ".class, " +
        literal(property) +
        ")"
      : "__tv";
    return (
      "if (" +
      split.guard() +
      " == null) { final " +
      choice.argumentText() +
      " __arg = " +
      split.base() +
      "; out." +
      setter +
      "(__arg); } else { final " +
      (inferred ? "Object" : split.gives().toString()) +
      " __tv = " +
      split.partial() +
      "; if (__tv != null) { final " +
      choice.argumentText() +
      " __arg = " +
      converted +
      "; out." +
      setter +
      "(__arg); } } "
    );
  }

  /**
   * Whether a conversion result of type {@code gives} reaches the member parameter {@code
   * parameter} only through a check of its class: both name no type variable, the result is a
   * reference the parameter does not take as it is, and the parameter's class, its wrapper where it
   * is primitive, is a subtype of the result's erasure, and the result's type arguments, where it
   * and the parameter are one class given arguments, fit the parameter's ({@link
   * PairingRules#argumentsFit}), since the check sees only the value's class. The result is then
   * written after {@code FixedClass.result} checks it, as the runtime checks it, and refused by
   * name otherwise.
   */
  private boolean reachesByCheck(final TypeMirror gives, final TypeMirror parameter) {
    if (gives == null || parameter == null || gives.getKind().isPrimitive()) return false;
    if (mirrorProps().mentionsTypeVariable(gives) || mirrorProps().mentionsTypeVariable(parameter)) return false;
    final var types = processingEnv.getTypeUtils();
    if (types.isAssignable(gives, parameter)) return false;
    return types.isSubtype(boxedErasure(parameter), types.erasure(gives)) && rules.argumentsFit(gives, parameter);
  }

  /**
   * The check of a value read for a transform: the class the transform takes, as the cast after the
   * check names it, and what a call to {@code FixedClass.argument} takes after the value.
   */
  private record ArgumentCheck(String type, String arguments) {}

  /**
   * Whether a value of a property declared as {@code declared}, held as {@code held}, reaches a
   * transform taking {@code accepts} only through a check of its class: the property is declared
   * with a type variable, which the bridge holds as its erasure, and {@code accepts} is a class
   * naming no type variable and given no type arguments, a subtype of that erasure the value is not
   * shown to be. The check sees the value's class, which is all of such a type. The value is then
   * handed over after {@code FixedClass.argument} checks it, as the runtime checks it, and refused
   * by name otherwise.
   */
  private boolean readsByCheck(final TypeMirror declared, final TypeMirror held, final TypeMirror accepts) {
    if (declared == null || held == null || accepts == null) return false;
    if (declared.getKind() != TypeKind.TYPEVAR || accepts.getKind().isPrimitive()) return false;
    if (mirrorProps().mentionsTypeVariable(accepts)) return false;
    final var types = processingEnv.getTypeUtils();
    final var erased = types.erasure(accepts);
    if (!types.isSameType(accepts, erased) || types.isAssignable(held, accepts)) return false;
    return types.isSubtype(erased, types.erasure(held));
  }

  /**
   * The check a value of {@code property} of {@code owner} takes before a transform taking {@code
   * accepts}.
   */
  private ArgumentCheck argumentCheck(final TypeMirror accepts, final TypeElement owner, final String property) {
    final var types = processingEnv.getTypeUtils();
    return new ArgumentCheck(
      accepts.toString(),
      types.erasure(accepts) + ".class, " + types.erasure(owner.asType()) + ".class, " + literal(property)
    );
  }

  /** {@code value}, checked as {@code check} says and cast to the class it names, or as it is. */
  private static String checkedArgument(final ArgumentCheck check, final String value) {
    if (check == null) return value;
    return "((" + check.type() + ") " + FIXED_CLASS + ".argument(" + value + ", " + check.arguments() + "))";
  }

  /**
   * {@code value}, a conversion result, checked against the class {@code parameter} takes and cast
   * to it, for property {@code property} of {@code owner}. A primitive parameter takes its wrapper,
   * and a null result its default, as a constructor or builder argument takes it.
   */
  private String checkedResult(
    final String value,
    final TypeMirror parameter,
    final TypeElement owner,
    final String property
  ) {
    final var expected = boxedErasure(parameter);
    final var ownerClass = processingEnv.getTypeUtils().erasure(owner.asType()) + ".class";
    if (parameter.getKind().isPrimitive()) {
      return (
        "((" +
        expected +
        ") " +
        FIXED_CLASS +
        ".resultOr(" +
        value +
        ", " +
        expected +
        ".class, " +
        ownerClass +
        ", " +
        literal(property) +
        ", " +
        defaultLiteralFor(parameter) +
        "))"
      );
    }
    return (
      "((" +
      parameter +
      ") " +
      FIXED_CLASS +
      ".result(" +
      value +
      ", " +
      expected +
      ".class, " +
      ownerClass +
      ", " +
      literal(property) +
      "))"
    );
  }

  /**
   * {@code value}, a conversion result of type {@code gives}, as a call to {@code member} with
   * parameter {@code parameter} takes it: checked against the parameter where only a check reaches
   * it, cast to the parameter where another overload would bind it as it is, and as it is
   * otherwise.
   */
  private String resultArgument(
    final String value,
    final TypeMirror gives,
    final TypeMirror parameter,
    final TypeElement owner,
    final String property
  ) {
    if (gives == null || parameter == null) return value;
    if (reachesByCheck(gives, parameter) || unboxedThroughCheck(gives, parameter)) {
      return writtenResult(value, gives, parameter, owner, property);
    }
    final var types = processingEnv.getTypeUtils();
    if (
      !mirrorProps().mentionsTypeVariable(parameter) &&
      !types.isSameType(gives, parameter) &&
      types.isAssignable(gives, parameter)
    ) {
      return "((" + parameter + ") " + value + ")";
    }
    return value;
  }

  /**
   * Whether a transform result of type {@code gives} reaches the chosen setter parameter {@code
   * parameter} only through a cast: both are reference types naming no type variable, the result is
   * not assignable to the parameter, and the parameter's erasure is a proper subtype of the
   * result's, so the cast narrows. A result the parameter takes as it is needs no cast, and a
   * result of a type the parameter's does not reach admits none.
   */
  private boolean castsToChosen(final TypeMirror gives, final TypeMirror parameter) {
    if (gives == null || parameter == null) return false;
    if (gives.getKind().isPrimitive() || parameter.getKind().isPrimitive()) return false;
    if (mirrorProps().mentionsTypeVariable(gives) || mirrorProps().mentionsTypeVariable(parameter)) return false;
    final var types = processingEnv.getTypeUtils();
    if (types.isAssignable(gives, parameter)) return false;
    return types.isSubtype(types.erasure(parameter), types.erasure(gives));
  }

  /**
   * The type a generated call hands the chosen setter of {@code to} its value as, so that javac
   * binds that setter and no other overload. The bridge names a generic class raw, and every member
   * of a raw class takes the erasure of its declared parameter, so there it is that erasure: an
   * inherited {@code setV(T)} that {@code Tgt extends Base<String>} reads as {@code setV(String)}
   * is {@code setV(Object)} on the raw {@code Tgt}, and a {@code String} argument would bind an
   * overload such as {@code setV(CharSequence)} instead. On a class with no type parameters it is
   * the parameter as a member of {@code to}. Where the value spreads into a variable-arity setter,
   * it is the array's component.
   */
  private TypeMirror boundArgument(final TypeElement to, final MirrorSetters.SetterChoice choice) {
    if (to.getTypeParameters().isEmpty() || choice.member() == null) return choice.argument();
    final var declared = processingEnv.getTypeUtils().erasure(choice.member().getParameters().getFirst().asType());
    return choice.spreads() && declared instanceof ArrayType array ? array.getComponentType() : declared;
  }

  /** {@link #boundArgument} as a generated local declares it. */
  private String boundArgumentText(final TypeElement to, final MirrorSetters.SetterChoice choice) {
    return to.getTypeParameters().isEmpty() || choice.member() == null
      ? choice.argumentText()
      : boundArgument(to, choice).toString();
  }

  /** One constructor argument: the parameter it fills, its declared type, and its expression. */
  private record CtorSlot(String name, TypeMirror type, String expr) {}

  /** A method call or an instance creation somewhere in an emitted expression. */
  private static final Pattern CALL_OR_NEW = Pattern.compile("[\\w$]\\s*\\(|\\bnew\\b");

  /**
   * A constructor call over {@code slots}, in parameter order. With a {@code hoistInto} package, an
   * argument that calls a method or creates an instance is first evaluated into a {@code final}
   * local, and the call names the local. Null {@code hoistInto} emits every argument inline.
   *
   * <p>javac emits the {@code new} of a constructor call before the code of its arguments, so an
   * argument that allocates, such as a nested bridge's {@code forward}, allocates after the outer
   * object. Evaluating the arguments first puts the outer allocation last, directly ahead of the
   * constructor that writes its fields.
   *
   * <p>Arguments are evaluated in parameter order either way, which is the order the runtime
   * converts the components in. The locals come first, in parameter order, and then the call, whose
   * remaining arguments run in parameter order after them. So a local is declared only for an
   * argument ahead of every inline one that calls or creates something: once an argument that does
   * has to stay inline, every argument after it stays inline too. A local is declared with the
   * argument's type, or with its erasure where the type names a type variable the static method
   * does not declare; an argument stays inline when the bridge's package cannot name that.
   */
  private String construct(final TypeElement to, final List<CtorSlot> slots, final String hoistInto) {
    final var toFq = to.getQualifiedName().toString();
    final var types = processingEnv.getTypeUtils();
    final var erased = erasedArguments(to, slots);
    final var localTypes = new LinkedHashMap<String, TypeMirror>();
    if (hoistInto != null) {
      for (final var slot : slots) {
        if (!CALL_OR_NEW.matcher(slot.expr()).find()) continue;
        final var local = mirrorProps().mentionsTypeVariable(slot.type()) ? types.erasure(slot.type()) : slot.type();
        if (!writableFrom(local, hoistInto)) break;
        localTypes.put(slot.name(), local);
      }
    }
    final var hoisted = localTypes.keySet();
    final var args = slots
      .stream()
      .map(slot ->
        hoisted.contains(slot.name())
          ? "__cv_" + slot.name()
          : erased.contains(slot.name())
            ? erasingBridge + ".<" + types.erasure(slot.type()) + ">__erased(" + slot.expr() + ")"
            : slot.expr()
      )
      .collect(Collectors.joining(", "));
    if (!erased.isEmpty()) erasedArgumentUsed = true;
    final var call = "new " + toFq + "(" + args + ")";
    if (hoisted.isEmpty()) return call;
    final var sb = new StringBuilder("{ ");
    for (final var slot : slots) {
      if (!hoisted.contains(slot.name())) continue;
      sb.append("final ").append(localTypes.get(slot.name())).append(" __cv_").append(slot.name()).append(" = ");
      sb.append(slot.expr()).append("; ");
    }
    return sb.append("return ").append(call).append("; }").toString();
  }

  /**
   * The slots of a call to a generic record's canonical constructor whose argument is passed as the
   * erasure of its declared type. The bridge names the record raw, so a component typed by its
   * variable takes the erasure, and an argument of a narrower type binds another constructor of the
   * same arity that takes that type, where the runtime calls the canonical one. Passed as the
   * erasure, the argument binds the canonical constructor alone. Empty where the record has no
   * other constructor of the call's arity. A generic bean with a second constructor of its
   * name-matched constructor's arity is refused before its rebuild is emitted, so a bean has no
   * such call.
   */
  private Set<String> erasedArguments(final TypeElement to, final List<CtorSlot> slots) {
    if (to.getKind() != ElementKind.RECORD || to.getTypeParameters().isEmpty()) return Set.of();
    final var sameArity = ElementFilter.constructorsIn(to.getEnclosedElements())
      .stream()
      .filter(c -> c.getParameters().size() == slots.size())
      .count();
    if (sameArity < 2) return Set.of();
    return slots
      .stream()
      .filter(slot -> mirrorProps().mentionsTypeVariable(slot.type()))
      .map(CtorSlot::name)
      .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Whether a local of {@code type} can be declared in a static method of a class in {@code
   * bridgePkg}: every class it names, at any depth, is nameable there, and it names no type
   * variable.
   */
  private boolean writableFrom(final TypeMirror type, final String bridgePkg) {
    return switch (type) {
      case final DeclaredType declared -> declared.asElement() instanceof TypeElement element &&
      nameableFrom(element, bridgePkg) &&
      declared
        .getTypeArguments()
        .stream()
        .allMatch(arg -> writableFrom(arg, bridgePkg));
      case final ArrayType array -> writableFrom(array.getComponentType(), bridgePkg);
      case final WildcardType wildcard -> (wildcard.getExtendsBound() == null ||
        writableFrom(wildcard.getExtendsBound(), bridgePkg)) &&
      (wildcard.getSuperBound() == null || writableFrom(wildcard.getSuperBound(), bridgePkg));
      default -> type.getKind().isPrimitive();
    };
  }

  /**
   * Why no rung applied. A forced strategy names the one that was asked for and what to switch to;
   * AUTO ran the whole ladder, so it names what any of them would have needed.
   */
  private static String noStrategyMessage(
    final TypeElement annotationSite,
    final String toFq,
    final String writeStrategy
  ) {
    return switch (writeStrategy) {
      case "CONSTRUCTOR" -> "@Bridge writeStrategy = CONSTRUCTOR on " +
      annotationSite.getQualifiedName() +
      " (target " +
      toFq +
      "): no constructor the bridge's package can call whose parameter names match the" +
      " bridge fields. Switch to AUTO, BUILDER, or SETTERS, or add a name-matched constructor.";
      case "BUILDER" -> "@Bridge writeStrategy = BUILDER on " +
      annotationSite.getQualifiedName() +
      " (target " +
      toFq +
      "): no static builder() method returning a builder class. Switch to AUTO," +
      " CONSTRUCTOR, or SETTERS, or add a builder().";
      case "SETTERS" -> "@Bridge writeStrategy = SETTERS on " +
      annotationSite.getQualifiedName() +
      " (target " +
      toFq +
      "): no no-arg constructor the bridge's package can call. Switch to AUTO," +
      " CONSTRUCTOR, or BUILDER, or add a public no-arg constructor.";
      default -> "@Bridge: " +
      toFq +
      " has no usable construction strategy — needs a record canonical constructor, a" +
      " constructor whose parameter names match the fields, a static builder(), or a" +
      " no-arg constructor with setters";
    };
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
    final var byName = new LinkedHashMap<String, Field>();
    for (final var p : beanProperties(type)) byName.put(p.name(), new Field(p.name(), p.type()));
    // The order the runtime walks a bean's properties in: those a field of the same name backs, in
    // the order the class and its superclasses declare the fields, the root class first, then the
    // rest by name. A bean's properties are converted in this order on both paths.
    final var hierarchy = new ArrayDeque<TypeElement>();
    for (TypeElement c = type; c != null; ) {
      if (c.getQualifiedName().contentEquals("java.lang.Object")) break;
      hierarchy.push(c);
      c = c.getSuperclass() instanceof DeclaredType parent ? (TypeElement) parent.asElement() : null;
    }
    final var ordered = new LinkedHashMap<String, Field>();
    for (final var c : hierarchy) {
      for (final var f : ElementFilter.fieldsIn(c.getEnclosedElements())) {
        if (f.getModifiers().contains(Modifier.STATIC)) continue;
        final var name = f.getSimpleName().toString();
        final var property = byName.get(name);
        if (property != null) ordered.putIfAbsent(name, property);
      }
    }
    byName
      .keySet()
      .stream()
      .filter(name -> !ordered.containsKey(name))
      .sorted()
      .forEach(name -> ordered.put(name, byName.get(name)));
    return List.copyOf(ordered.values());
  }

  /**
   * The types a pair's properties are planned and read as, and the casts its reads and writes need.
   *
   * @param source the source's properties, each as the pair reads it
   * @param target the target's properties, each as the pair reads it
   * @param sourceReads per source property, the class a read of it is cast to before it is held as
   *     the type the pair reads it as, where the type the read returns does not convert to that
   * @param targetReads the same per target property
   * @param forwardCasts per target property, the class {@code forward} casts the value it writes
   *     to, where the type it reads is wider than the target's own
   * @param backwardCasts the same per source property, for {@code backward} and {@code patch}
   * @param forwardChecks per target property whose class fixes the variable a value handed across
   *     unchanged is declared with, what {@code FixedClass.checked} takes after the value
   * @param backwardChecks the same per source property, for {@code backward} and {@code patch}
   * @param rawAccess whether any property is read or written through a member of a class the bridge
   *     names raw whose type erasure changes, which javac reports as unchecked
   */
  private record PairedFields(
    List<Field> source,
    List<Field> target,
    Map<String, String> sourceReads,
    Map<String, String> targetReads,
    Map<String, String> forwardCasts,
    Map<String, String> backwardCasts,
    Map<String, String> forwardChecks,
    Map<String, String> backwardChecks,
    boolean rawAccess
  ) {
    /** {@code value}, cast to what {@code casts} names for {@code name}, or as is when nothing. */
    String castInto(final Map<String, String> casts, final String name, final String value) {
      final var cast = casts.get(name);
      return cast == null ? value : "((" + cast + ") " + value + ")";
    }

    /**
     * {@code value}, checked against the class {@code checks} names for {@code name}, or as is when
     * nothing. The check returns the value as the type it is held as, so a cast around it is the
     * one the write needs anyway.
     */
    String checkedInto(final Map<String, String> checks, final String name, final String value) {
      final var arguments = checks.get(name);
      return arguments == null ? value : FIXED_CLASS + ".checked(" + value + ", " + arguments + ")";
    }
  }

  /**
   * Whether {@code sourceType} names a type variable {@code source} declares, or {@code targetType}
   * one {@code target} declares.
   */
  private static boolean namesOwnVariable(
    final TypeElement source,
    final TypeMirror sourceType,
    final TypeElement target,
    final TypeMirror targetType
  ) {
    return Stream.concat(
      source
        .getTypeParameters()
        .stream()
        .map(variable -> MirrorProps.mentionsVariable(sourceType, variable.asType())),
      target
        .getTypeParameters()
        .stream()
        .map(variable -> MirrorProps.mentionsVariable(targetType, variable.asType()))
    ).anyMatch(Boolean::booleanValue);
  }

  /**
   * The types each source property and the target property it pairs with are read as. The shared
   * spec decides whether a pair reads as members of its own classes or as declared ({@link
   * PairingRules#readsAsDeclared}). Read as declared, both are read as the one type the superclass
   * they share declares, with each type variable erased, since no static method of the bridge can
   * name the variable. The target's declared type reads each type variable its class declares as
   * the source class's in the same position ({@link PairingRules#alignedToSource}), so two generic
   * classes passing a variable across convert it unchanged. A pair whose declared types differ
   * keeps them, and is refused in their terms.
   *
   * <p>The bridge names a generic class raw, so every member it reaches on one returns its erased
   * type: a property {@code class Page<X> extends Base<String, X>} fixes to {@code String} reads
   * back as {@code Object}. Such a read is cast to the erasure of the type it is held as. A side
   * that fixes the variable a pair reads as declared, such as {@code class Names extends
   * Base<String>}, is written through a cast to its own erasure where that is narrower than the
   * read.
   *
   * <p>A pair in {@code ownConversions}, which a transform or a {@code @ViaMapper} converts, is
   * read as neither of these: the conversion takes what the source gives and returns what the
   * target takes, so each side is held as the bridge reaches it ({@link #heldAs}).
   *
   * <p>Null after refusing a pair whose declared types name a variable of one of the two classes
   * and which the shared rules refuse in those terms.
   */
  private PairedFields pairedFields(
    final TypeElement source,
    final TypeElement target,
    final List<Field> everySourceField,
    final List<Field> sourceFields,
    final List<Field> targetFields,
    final Map<String, String> renames,
    final Set<String> ownConversions,
    final Set<String> lifted
  ) {
    final var types = processingEnv.getTypeUtils();
    final var pairedSource = new ArrayList<>(sourceFields);
    final var pairedTarget = new ArrayList<>(targetFields);
    final var sourceReads = new HashMap<String, String>();
    final var targetReads = new HashMap<String, String>();
    final var forwardCasts = new HashMap<String, String>();
    final var backwardCasts = new HashMap<String, String>();
    final var forwardChecks = new HashMap<String, String>();
    final var backwardChecks = new HashMap<String, String>();
    // The methods read and write every property of both sides, a dropped one included, which
    // backward fills with a default and patch copies from base.
    boolean rawAccess = false;
    for (final var f : everySourceField) rawAccess |= !types.isSameType(asReachedThrough(source, f), f.type());
    for (final var f : targetFields) rawAccess |= !types.isSameType(asReachedThrough(target, f), f.type());
    // A setter a generic class inherits with its superclass's variable is reached raw too, and a
    // call to it through the raw class is unchecked, whatever its getter returns.
    for (final var f : everySourceField) rawAccess |= setterReachedRaw(source, f);
    for (final var f : targetFields) rawAccess |= setterReachedRaw(target, f);
    for (int i = 0; i < sourceFields.size(); i++) {
      final var sf = sourceFields.get(i);
      final var tgtName = renames.getOrDefault(sf.name(), sf.name());
      if (!hasField(targetFields, tgtName)) continue;
      final var tf = fieldByName(targetFields, tgtName);
      final var sourceOwn = asReachedThrough(source, sf);
      final var targetOwn = asReachedThrough(target, tf);
      var sourceRead = sf.type();
      var targetRead = tf.type();
      if (ownConversions.contains(sf.name())) {
        // A conversion of the pair's own takes each side as it is, so neither is read as the
        // other's type: each is held as the bridge reaches it.
        sourceRead = lifted.contains(sf.name()) ? withVariablesErased(sf.type()) : heldAs(sf.type());
        targetRead = lifted.contains(sf.name()) ? withVariablesErased(tf.type()) : heldAs(tf.type());
        pairedSource.set(i, new Field(sf.name(), sourceRead));
        pairedTarget.set(pairedTarget.indexOf(tf), new Field(tf.name(), targetRead));
      } else if (rules.readsAsDeclared(sf.type(), tf.type())) {
        final var sourceDeclared = declaredTypeOf(source, sf);
        final var asDeclared = declaredTypeOf(target, tf);
        final var targetDeclared = rules.alignedToSource(asDeclared, source.asType(), target.asType());
        final var same = types.isSameType(sourceDeclared, targetDeclared);
        // A pair naming a type variable of one of the two classes is decided in those terms, the
        // target's read as the source's, as the runtime decides it: one passing a variable into a
        // slot another variable fills, such as A against B, is refused in the shared rules' words.
        if (!same && namesOwnVariable(source, sourceDeclared, target, asDeclared)) {
          final var decision = rules.decidePair(sourceDeclared, targetDeclared, sf.name());
          if (decision instanceof PairDecision.Incompatible<TypeMirror> refused) {
            error(
              source,
              "@Bridge " + source.getSimpleName() + " -> " + target.getSimpleName() + ": " + refused.message()
            );
            return null;
          }
        }
        // A target read across in terms of the source's variables, such as InnerDto<T> against
        // Inner<T>, is planned in those terms, as the runtime decides the pair.
        if (!same && types.isSameType(asDeclared, targetDeclared)) continue;
        sourceRead = withVariablesErased(sourceDeclared);
        targetRead = same ? sourceRead : withVariablesErased(targetDeclared);
        pairedSource.set(i, new Field(sf.name(), sourceRead));
        pairedTarget.set(pairedTarget.indexOf(tf), new Field(tf.name(), targetRead));
        // A value handed across unchanged is written through a cast where the side it lands on is
        // narrower; one the pair converts is written as the conversion returns it.
        if (same && !types.isAssignable(sourceRead, targetOwn)) {
          forwardCasts.put(tf.name(), types.erasure(targetOwn).toString());
        }
        if (same && !types.isAssignable(sourceRead, sourceOwn)) {
          backwardCasts.put(sf.name(), types.erasure(sourceOwn).toString());
        }
        // A value handed across unchanged is checked against the class the side it lands on fixes
        // the variable to, as the runtime checks it; a container that cannot be shown to fit is
        // refused, in either direction. A lenient bridge leaves properties unmatched, and checks
        // the ones it pairs as a strict one does.
        if (same) {
          // Each side as a member of its class, with the variables it passes through kept, so their
          // bounds say what it holds, as the runtime reads it. The declared type says whether the
          // value is copied across or shared, the type the runtime decides it from.
          final var intoTarget = rules.fixedFit(sourceDeclared, sf.type(), tf.type(), sf.name());
          final var intoSource = rules.fixedFit(sourceDeclared, tf.type(), sf.type(), sf.name());
          final var refused =
            intoTarget instanceof FixedFit.Refused<TypeMirror> forward
              ? forward.message()
              : intoSource instanceof FixedFit.Refused<TypeMirror> backward
                ? backward.message()
                : null;
          if (refused != null) {
            error(source, "@Bridge " + source.getSimpleName() + " -> " + target.getSimpleName() + ": " + refused);
            return null;
          }
          if (intoTarget instanceof FixedFit.Check<TypeMirror> check) {
            forwardChecks.put(tf.name(), fixedCheckArguments(check.fixed(), target, tf.name()));
          }
          if (intoSource instanceof FixedFit.Check<TypeMirror> check) {
            backwardChecks.put(sf.name(), fixedCheckArguments(check.fixed(), source, sf.name()));
          }
        }
      }
      if (!types.isAssignable(sourceOwn, sourceRead)) {
        sourceReads.put(sf.name(), types.erasure(sourceRead).toString());
      }
      if (!types.isAssignable(targetOwn, targetRead)) {
        targetReads.put(tf.name(), types.erasure(targetRead).toString());
      }
    }
    return new PairedFields(
      List.copyOf(pairedSource),
      List.copyOf(pairedTarget),
      sourceReads,
      targetReads,
      forwardCasts,
      backwardCasts,
      forwardChecks,
      backwardChecks,
      rawAccess
    );
  }

  /**
   * What a call to {@code FixedClass.checked} takes after the value, for a property {@code name} of
   * {@code owner} whose class fixes the variable to {@code fixed}: both class literals and the
   * property's name.
   */
  private String fixedCheckArguments(final TypeMirror fixed, final TypeElement owner, final String name) {
    final var types = processingEnv.getTypeUtils();
    return types.erasure(fixed) + ".class, " + types.erasure(owner.asType()) + ".class, " + literal(name);
  }

  /**
   * Whether the bridge writes {@code field} of {@code owner} through a setter whose declared
   * parameter names a type variable while it names {@code owner} raw, a call javac reports as
   * unchecked.
   */
  private boolean setterReachedRaw(final TypeElement owner, final Field field) {
    if (owner.getTypeParameters().isEmpty()) return false;
    final var member = setterChoice(owner, field.name()).member();
    return member != null && mirrorProps().mentionsTypeVariable(member.getParameters().getFirst().asType());
  }

  /**
   * The type {@code field} of {@code owner} has where the bridge reaches it. The bridge names a
   * generic class raw, and a member of a raw class has the erasure of its declared type, whatever a
   * superclass's arguments fix it to.
   */
  private TypeMirror asReachedThrough(final TypeElement owner, final Field field) {
    if (owner.getTypeParameters().isEmpty()) return field.type();
    return processingEnv.getTypeUtils().erasure(declaredTypeOf(owner, field));
  }

  /**
   * {@code type} as a local the bridge declares can be typed: each type argument outside the bound
   * its class declares for it, at any depth, an unbounded wildcard instead, which every bound
   * admits. A variable read as its erasure can fall outside its own bound, as {@code Enum} is
   * outside {@code EnumMap}'s {@code K extends Enum<K>}. The pair is still planned as {@code type};
   * only the declaration changes, and the value it holds is the same.
   */
  private TypeMirror declarable(final TypeMirror type) {
    final var types = processingEnv.getTypeUtils();
    if (type instanceof ArrayType array) return types.getArrayType(declarable(array.getComponentType()));
    if (!(type instanceof DeclaredType declared) || declared.getTypeArguments().isEmpty()) return type;
    final var element = (TypeElement) declared.asElement();
    final var arguments = declared
      .getTypeArguments()
      .stream()
      .map(a -> a instanceof WildcardType ? a : declarable(a))
      .toArray(TypeMirror[]::new);
    final var parameters = element.getTypeParameters().stream().<TypeMirror>map(Element::asType).toList();
    final var given = List.of(arguments);
    var changed = false;
    for (int i = 0; i < arguments.length && i < parameters.size(); i++) {
      final var argument = arguments[i];
      if (argument instanceof WildcardType) continue;
      final var outside = element
        .getTypeParameters()
        .get(i)
        .getBounds()
        .stream()
        .anyMatch(bound -> !types.isSubtype(argument, mirrorProps().substitute(bound, parameters, given)));
      if (outside) {
        arguments[i] = types.getWildcardType(null, null);
        changed = true;
      }
    }
    if (!changed && given.equals(declared.getTypeArguments())) return type;
    return types.getDeclaredType(element, arguments);
  }

  /**
   * {@code type} with every type variable in it replaced by the variable's erasure, so {@code T}
   * reads as {@code Object} and {@code List<T>} as {@code List<Object>}. The container keeps its
   * element type, which is what decides that its elements are copied into a fresh container rather
   * than the container passed along; a static method can name the result.
   */
  private TypeMirror withVariablesErased(final TypeMirror type) {
    final var types = processingEnv.getTypeUtils();
    if (type instanceof TypeVariable) return types.erasure(type);
    if (type instanceof ArrayType array) return types.getArrayType(withVariablesErased(array.getComponentType()));
    if (!(type instanceof DeclaredType declared) || declared.getTypeArguments().isEmpty()) return type;
    if (!MirrorProps.typeVariableIn(declared)) return type;
    final var arguments = declared
      .getTypeArguments()
      .stream()
      .map(a ->
        a instanceof WildcardType w
          ? types.getWildcardType(
              w.getExtendsBound() == null ? null : withVariablesErased(w.getExtendsBound()),
              w.getSuperBound() == null ? null : withVariablesErased(w.getSuperBound())
            )
          : withVariablesErased(a)
      )
      .toArray(TypeMirror[]::new);
    return types.getDeclaredType((TypeElement) declared.asElement(), arguments);
  }

  /**
   * The type {@code field} is declared as where it is declared: a record component's own type, or
   * the return type of the getter as its declaring class writes it, before any subclass fixes the
   * declaring class's variables.
   */
  private TypeMirror declaredTypeOf(final TypeElement owner, final Field field) {
    if (owner.getKind() == ElementKind.RECORD) return field.type();
    final var getter = getterName(owner, field.name(), field.type());
    return ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(owner))
      .stream()
      .filter(m -> m.getSimpleName().contentEquals(getter) && m.getParameters().isEmpty())
      .findFirst()
      .orElseThrow()
      .getReturnType();
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

  /**
   * Whether the bridge, emitted into {@code bridgePkg}, can name the declared type of every field
   * it reads, reporting the first it cannot. Each read is hoisted into a local declared with the
   * field's own type: every source field for {@code forward}, and every target field for {@code
   * backward} and {@code patch} except one a forward-only transform gives them nothing to read. A
   * type the package cannot name makes that local, and with it the generated file, fail to compile
   * whatever the field's plan would have been.
   */
  private boolean fieldTypesNameable(
    final TypeElement source,
    final TypeElement target,
    final List<Field> sourceFields,
    final List<Field> targetFields,
    final Map<String, String> renames,
    final Set<String> forwardOnlyTransforms,
    final String bridgePkg
  ) {
    for (final var sf : sourceFields) {
      final var sides = new ArrayList<TypeMirror>(2);
      sides.add(sf.type());
      if (!forwardOnlyTransforms.contains(sf.name())) {
        sides.add(fieldByName(targetFields, renames.getOrDefault(sf.name(), sf.name())).type());
      }
      for (final var type : sides) {
        final var hidden = unnameableErasure(type, bridgePkg);
        if (hidden == null) continue;
        error(
          source,
          "@Bridge " +
            source.getSimpleName() +
            " -> " +
            target.getSimpleName() +
            ": field '" +
            sf.name() +
            "' is declared as '" +
            type +
            "', and " +
            hidden.getQualifiedName() +
            " cannot be named from " +
            packagePhrase(bridgePkg) +
            ", where the bridge is generated — the bridge reads the field into a local of that type." +
            " Make it and every class enclosing it public, or declare the @Bridge in its package."
        );
        return false;
      }
    }
    return true;
  }

  /**
   * The class {@code type} erases to when the bridge's package cannot name it, or null when it can.
   * An array is named through its component type.
   */
  private TypeElement unnameableErasure(final TypeMirror type, final String bridgePkg) {
    final var erased = processingEnv.getTypeUtils().erasure(type);
    if (erased instanceof ArrayType array) return unnameableErasure(array.getComponentType(), bridgePkg);
    if (!(erased instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) {
      return null;
    }
    return nameableFrom(element, bridgePkg) ? null : element;
  }

  private static String packagePhrase(final String pkg) {
    return pkg.isEmpty() ? "the unnamed package" : "package " + pkg;
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
