package io.github.eschizoid.telescope.codegen;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.eschizoid.telescope.annotations.UncheckedMapping;
import io.github.eschizoid.telescope.internal.pairing.Allocation;
import io.github.eschizoid.telescope.internal.pairing.BeanWriteStrategy;
import io.github.eschizoid.telescope.internal.pairing.ContainerAllocation;
import io.github.eschizoid.telescope.internal.pairing.ContainerView;
import io.github.eschizoid.telescope.internal.pairing.PairDecision;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
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
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Compile-time verification of {@code Telescope.map} / {@code Telescope.mapper} / {@code
 * Telescope.mapperForward} call sites. Walks every compiled class's tree for invocations of those
 * factories, extracts the statically-visible facts (class-literal source/target, inline {@code
 * Mapping} rows whose accessors are method references), and replays the construction-time pairing
 * decisions through the same shared spec the runtime uses — reporting the runtime's own diagnostic
 * text as compile errors anchored on the offending expression.
 *
 * <p>Verification is additive, never load-bearing. Anything not statically visible — a non-literal
 * class argument, a row built by a helper, a factory the scanner doesn't recognize — silently
 * defers to the construction-time backstop (a NOTE under {@code -Atelescope.verify.verbose}), so
 * the verifier can only add errors for provably-broken code, never make broken code pass. On a
 * compiler without the {@code com.sun.source} tree API the processor prints one NOTE and no-ops.
 *
 * <p>Options: {@code -Atelescope.verify=error|warn|off} (default {@code error}); {@code
 * -Atelescope.verify.verbose} notes skipped sites. Per-site exemption:
 * {@code @UncheckedMapping("reason")} on an enclosing field, method, constructor, or class.
 *
 * <p>Scope notes, mirroring the runtime exactly: {@code mapperForward} is lenient by contract, so
 * only its explicit rows are checked (no completeness). Rows that carry a conversion the verifier
 * can't check ({@code to(src, tgt, fwd, bwd)}, {@code toOneWay}, {@code enumTo}, {@code via}) are
 * claims only. Presence of a target-telescope / {@code constant} / {@code compute} row switches the
 * runtime into its permissive mode, so completeness checking is disabled for that site too. Nested
 * auto-recursed pairs are lenient about unmatched fields (as at runtime) but still shape-checked.
 * {@code toOrElse} / {@code toOrElseGet} are claims only: the runtime constructs them without a
 * shape gate, and this verifier never fires where construction succeeds. A row keyed to a type pair
 * the mapper never visits is reported with construction's refusal, but only when every row of the
 * call and every property on the walk can be read; otherwise construction decides alone. Under the
 * same condition, a bean property construction fills through a setter that cannot take the
 * property's own type, or through one of several setters none of which takes it more specifically,
 * is reported with construction's refusal, on the target and, for a mapper that also runs backward,
 * on the source.
 *
 * <p>A {@code cases} row is reported when a class it names is not a permitted subclass of a sealed
 * interface, when two rows pair the same case of the two sealed interfaces the call itself maps,
 * and when the walk from the call's pair reaches no sealed pair at all, so the row is never taken.
 * Which case a case without a row pairs with depends on bridges construction finds, so the rest is
 * left to construction.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions({ "telescope.verify", "telescope.verify.verbose" })
public final class MapperVerifierProcessor extends AbstractProcessor {

  private static final String TELESCOPE_FQN = "io.github.eschizoid.telescope.Telescope";
  private static final String MAPPING_FQN = "io.github.eschizoid.telescope.mapping.Mapping";
  // The telescope navigation calls that read a component of the path's current focus through an
  // accessor, so the first of them on a path names a property of the path's root.
  private static final Set<String> ROOT_HOPS = Set.of(
    "field",
    "each",
    "eachValue",
    "whenPresent",
    "list",
    "setField",
    "mapField",
    "optional"
  );
  private static final String WRITE_HINT_FQN = "io.github.eschizoid.telescope.mapping.WriteHint";
  private static final String NULL_HINT_FQN = "io.github.eschizoid.telescope.mapping.NullHint";
  private static final String CASES_FQN = "io.github.eschizoid.telescope.mapping.Cases";
  private static final Set<String> BRIDGE_FQNS = Set.of(
    "io.github.eschizoid.telescope.annotations.Bridge",
    "io.github.eschizoid.telescope.annotations.Bridges"
  );

  /**
   * Public no-arg constructor required by the {@link javax.annotation.processing.Processor} SPI.
   */
  public MapperVerifierProcessor() {
    super();
  }

  private Trees trees;
  private Types types;
  private Elements elements;
  private MirrorProps props;
  private PairingRules<TypeMirror> rules;
  private ContainerAllocation<TypeMirror> allocation;
  private MirrorSetters setters;
  // The compilation units entered so far, by source file, each as last entered; every one is
  // entered before the first call site is scanned.
  private final Map<URI, CompilationUnitTree> units = new LinkedHashMap<>();
  // The binary names of the classes a @Bridge in those units is on or names, read once they are all
  // entered; null until then.
  private Set<String> bridgeNamed;
  private Diagnostic.Kind reportKind;
  private boolean off;
  private boolean verbose;
  private boolean notedUnavailable;
  private String unavailableReason;

  @Override
  public synchronized void init(final ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    types = processingEnv.getTypeUtils();
    elements = processingEnv.getElementUtils();
    props = new MirrorProps(types, elements);
    rules = new PairingRules<>(props);
    allocation = new ContainerAllocation<>(props);
    setters = new MirrorSetters(types, elements, props);
    final var mode = processingEnv.getOptions().getOrDefault("telescope.verify", "error");
    if (!"error".equals(mode) && !"warn".equals(mode) && !"off".equals(mode)) {
      processingEnv
        .getMessager()
        .printMessage(
          Diagnostic.Kind.NOTE,
          "telescope: unrecognized -Atelescope.verify value '" + mode + "'; using 'error'."
        );
    }
    off = "off".equals(mode);
    reportKind = "warn".equals(mode) ? Diagnostic.Kind.WARNING : Diagnostic.Kind.ERROR;
    verbose = processingEnv.getOptions().containsKey("telescope.verify.verbose");
    if (off) return;
    try {
      trees = Trees.instance(processingEnv);
      // Scan each top-level type EXACTLY when its own analysis completes — its bodies (including
      // every nested type's) are fully attributed there, so tree queries are pure reads. Scanning
      // the whole unit on the first type's event would touch not-yet-attributed sibling types, and
      // attributing on demand corrupts javac's symbol bookkeeping for anonymous classes when the
      // final compile re-attributes the same trees (the classic reason body-level checkers run
      // post-analysis). ANALYZE-finished fires per type DECLARATION — nested types included — so
      // the top-level gate below is what prevents a nested type's subtree being scanned twice.
      // Registering from init keeps the processor packaging: one artifact, SPI-discovered.
      JavacTask.instance(processingEnv).addTaskListener(
        new TaskListener() {
          @Override
          public void finished(final TaskEvent event) {
            if (event.getKind() == TaskEvent.Kind.ENTER && event.getCompilationUnit() != null) {
              units.put(event.getCompilationUnit().getSourceFile().toUri(), event.getCompilationUnit());
              bridgeNamed = null;
              return;
            }
            if (event.getKind() != TaskEvent.Kind.ANALYZE) return;
            final var type = event.getTypeElement();
            if (type == null || type.getNestingKind() != NestingKind.TOP_LEVEL) return;
            final var path = trees.getPath(type);
            if (path == null) return;
            try {
              new CallSiteScanner().scan(path, null);
            } catch (final RuntimeException e) {
              // The verifier must never break a build except through its own diagnostics — an
              // uncaught exception in a task listener aborts the compile. Skip the type; the
              // construction-time validation still applies. A firing here is a verifier bug;
              // verbose mode appends the stack trace so the report is debuggable.
              final var detail = new StringWriter();
              if (verbose) e.printStackTrace(new PrintWriter(detail));
              processingEnv
                .getMessager()
                .printMessage(
                  Diagnostic.Kind.NOTE,
                  "telescope: mapping verification skipped for " +
                    type.getQualifiedName() +
                    " (" +
                    e +
                    "); construction-time validation still applies." +
                    (verbose ? "\n" + detail : "")
                );
            }
          }
        }
      );
    } catch (final RuntimeException e) {
      // Trees.instance / JavacTask.instance throw for non-javac environments; anything else
      // escaping here is a real init bug. Either way the reason is carried into the one NOTE so
      // the two causes are distinguishable in a report.
      trees = null;
      unavailableReason = e.toString();
    }
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    // All scanning happens in the post-ANALYZE task listener registered in init(); the rounds
    // themselves are a no-op. One NOTE when the tree API isn't available (non-javac compiler) —
    // verification is additive, the construction-time validation still applies.
    if (!off && trees == null && !notedUnavailable) {
      notedUnavailable = true;
      processingEnv
        .getMessager()
        .printMessage(
          Diagnostic.Kind.NOTE,
          "telescope: compile-time mapping verification needs the javac tree API; " +
            "skipping (construction-time validation still applies). Cause: " +
            unavailableReason
        );
    }
    return false;
  }

  /**
   * The binary names of the classes a {@code @Bridge} in this compilation is on or names, at any
   * depth of its values. Read from the compilation units rather than from the processing rounds,
   * because the bridge processor claims the annotation and a round then offers it to no other.
   */
  private Set<String> bridgeNamed() {
    if (bridgeNamed != null) return bridgeNamed;
    final var named = new HashSet<String>();
    for (final var unit : units.values()) {
      for (final var declaration : unit.getTypeDecls()) {
        if (trees.getElement(TreePath.getPath(unit, declaration)) instanceof TypeElement type) {
          collectBridges(type, named);
        }
      }
    }
    bridgeNamed = named;
    return named;
  }

  private void collectBridges(final TypeElement type, final Set<String> named) {
    for (final var mirror : type.getAnnotationMirrors()) {
      if (
        mirror.getAnnotationType().asElement() instanceof TypeElement annotation &&
        BRIDGE_FQNS.contains(annotation.getQualifiedName().toString())
      ) {
        named.add(binaryName(type));
        collectClasses(mirror, named);
      }
    }
    for (final var member : type.getEnclosedElements()) {
      if (member instanceof TypeElement nested) collectBridges(nested, named);
    }
  }

  private void collectClasses(final AnnotationMirror mirror, final Set<String> named) {
    for (final var value : mirror.getElementValues().values()) collectClasses(value, named);
  }

  private void collectClasses(final AnnotationValue value, final Set<String> named) {
    switch (value.getValue()) {
      case final DeclaredType type when type.asElement() instanceof TypeElement element -> named.add(
        binaryName(element)
      );
      case final AnnotationMirror nested -> collectClasses(nested, named);
      case final List<?> values -> {
        for (final var each : values) {
          if (each instanceof AnnotationValue element) collectClasses(element, named);
        }
      }
      default -> {
      }
    }
  }

  /**
   * One statically-recognized accessor reference: normalized property name, its declared type (as a
   * member of the accessor's own class), and that owning class. The owner matters because rows may
   * be keyed to NESTED pairs — {@code to(UserEntity::name, UserDto::fullName)} inside {@code
   * map(CompanyEntity, CompanyDto, ...)} — which the runtime groups by the accessors' classes; such
   * rows claim nothing at the call's top-level pair. {@code method} is the accessor's own name, as
   * written, before any {@code get} or {@code is} is stripped.
   */
  private record Prop(String name, TypeMirror type, TypeElement owner, String method) {}

  /** What a row does at the pair it is keyed to, as far as which pairs construction visits. */
  private enum RowKind {
    /** {@code to(src, tgt)}: claims the target property and converts it as an unclaimed one is. */
    PASS_THROUGH,
    /** A row carrying its own conversion: claims the target property, and reaches no pair. */
    CONVERTING,
    /** A row through a telescope path, or {@code constant} / {@code compute} / {@code when}. */
    PATH,
    /** {@code drop}, which construction never refuses as unvisited. */
    DROP,
  }

  /**
   * A row and the pair construction keys it to. The methods are the accessors' own names, null for
   * a side given as a telescope path.
   */
  private record KeyedRow(
    Tree at,
    TypeElement source,
    TypeElement target,
    RowKind kind,
    String sourceMethod,
    String targetMethod
  ) {}

  /** A pair's properties on each side, as members of the pair's classes and as declared. */
  private record PairSides(
    Map<String, TypeMirror> source,
    Map<String, TypeMirror> target,
    Map<String, TypeMirror> declaredSource,
    Map<String, TypeMirror> declaredTarget
  ) {}

  /** What a walk of the pairs construction visits does at each pair, besides following it. */
  private interface PairVisitor {
    /**
     * Whether construction walks the pair rather than converting it some other way. {@code keyed}
     * holds when a row is keyed to the pair, and {@code root} for the call's own pair.
     */
    boolean walks(TypeElement source, TypeElement target, boolean keyed, boolean root);

    /** Construction fills {@code targetName} of the pair's target from {@code sourceName}. */
    void fills(TypeElement source, TypeElement target, PairSides sides, String sourceName, String targetName);
  }

  /**
   * Whether a bridge of its own may convert the pair of {@code source} and {@code target}: either
   * class carries a {@code @Bridge}, a {@code @Bridge} this compilation sees names either one, or a
   * class exists under a name a generated bridge of the source takes, {@code <Source>Bridge} or
   * {@code <Source>To<Target>Bridge} in the source's package, or {@code <Source>Bridge} beside the
   * source in its enclosing class, which is where the runtime looks for a nested source's bridge.
   */
  private boolean bridgeVisible(final TypeElement source, final TypeElement target) {
    for (final var side : List.of(source, target)) {
      if (bridgeNamed().contains(binaryName(side))) return true;
      for (final var mirror : side.getAnnotationMirrors()) {
        final var annotation = mirror.getAnnotationType().asElement();
        if (annotation instanceof TypeElement type && BRIDGE_FQNS.contains(type.getQualifiedName().toString())) {
          return true;
        }
      }
    }
    final var simple = source.getSimpleName().toString();
    final var pkg = elements.getPackageOf(source).getQualifiedName().toString();
    final var prefix = pkg.isEmpty() ? "" : pkg + ".";
    final var names = new ArrayList<String>();
    names.add(prefix + simple + "Bridge");
    names.add(prefix + simple + "To" + target.getSimpleName() + "Bridge");
    if (source.getEnclosingElement() instanceof TypeElement enclosing) {
      names.add(enclosing.getQualifiedName() + "." + simple + "Bridge");
    }
    return names.stream().anyMatch(name -> elements.getTypeElement(name) != null);
  }

  private final class CallSiteScanner extends TreePathScanner<Void, Void> {

    @Override
    public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
      verifyIfTelescopeFactory(node);
      return super.visitMethodInvocation(node, unused);
    }

    private void verifyIfTelescopeFactory(final MethodInvocationTree node) {
      final var invoked = elementAt(node.getMethodSelect());
      if (!(invoked instanceof ExecutableElement method)) return;
      if (!(method.getEnclosingElement() instanceof TypeElement owner)) return;
      if (!owner.getQualifiedName().contentEquals(TELESCOPE_FQN)) return;
      final var name = method.getSimpleName().toString();
      final boolean strict;
      switch (name) {
        case "map", "mapper" -> strict = true;
        case "mapperForward" -> strict = false;
        default -> {
          return;
        }
      }
      if (suppressed()) return;
      verifyPairCall(node, strict);
    }

    private boolean suppressed() {
      for (var path = getCurrentPath(); path != null; path = path.getParentPath()) {
        final var kind = path.getLeaf().getKind();
        if (
          kind == Tree.Kind.METHOD ||
          kind == Tree.Kind.VARIABLE ||
          kind == Tree.Kind.CLASS ||
          kind == Tree.Kind.RECORD ||
          kind == Tree.Kind.INTERFACE ||
          kind == Tree.Kind.ENUM
        ) {
          final var element = trees.getElement(path);
          if (element != null && element.getAnnotation(UncheckedMapping.class) != null) return true;
        }
      }
      return false;
    }

    private void verifyPairCall(final MethodInvocationTree node, final boolean strict) {
      final var args = node.getArguments();
      if (args.size() < 2) return;
      final var srcType = classLiteral(args.get(0));
      final var tgtType = classLiteral(args.get(1));
      if (srcType == null || tgtType == null) {
        note(
          node,
          "telescope: mapping not statically verifiable (non-literal class argument); deferring to construction"
        );
        return;
      }
      final var srcEl = props.elementOf(srcType);
      final var tgtEl = props.elementOf(tgtType);
      if (
        srcEl != null &&
        tgtEl != null &&
        rules.decidePair(srcType, tgtType, "", !strict) instanceof PairDecision.DispatchCases
      ) {
        verifySealedCall(args, srcEl, tgtEl);
        return;
      }
      if (srcEl == null || tgtEl == null || !rules.reflectable(srcType) || !rules.reflectable(tgtType)) {
        note(
          node,
          "telescope: mapping not statically verifiable (non-reflectable class argument); deferring to construction"
        );
        return;
      }

      final var srcSimple = srcEl.getSimpleName().toString();
      final var tgtSimple = tgtEl.getSimpleName().toString();
      final var claimedSrc = new LinkedHashSet<String>();
      final var claimedTgt = new LinkedHashSet<String>();
      final var hintTargets = new HashSet<String>();
      var analyzable = true;
      var permissive = false;

      for (var i = 2; i < args.size(); i++) {
        final var arg = args.get(i);
        if (!(arg instanceof MethodInvocationTree row)) {
          analyzable = false;
          continue;
        }
        final var rowMethod = elementAt(row.getMethodSelect());
        if (
          !(rowMethod instanceof ExecutableElement rowExec) ||
          !(rowExec.getEnclosingElement() instanceof TypeElement rowOwner)
        ) {
          analyzable = false;
          continue;
        }
        final var ownerFqn = rowOwner.getQualifiedName().toString();
        if (WRITE_HINT_FQN.equals(ownerFqn)) {
          verifyWriteHint(row, rowExec, hintTargets);
          continue;
        }
        if (NULL_HINT_FQN.equals(ownerFqn)) continue;
        if (CASES_FQN.equals(ownerFqn)) {
          caseRow(row);
          continue;
        }
        if (!MAPPING_FQN.equals(ownerFqn)) {
          analyzable = false;
          continue;
        }
        analyzable &= verifyRow(row, rowExec, srcType, tgtType, srcSimple, tgtSimple, claimedSrc, claimedTgt, !strict);
        permissive |= isPermissiveRow(row, rowExec);
      }

      if (!analyzable) {
        note(node, "telescope: some mapping rows are not statically analyzable; completeness deferred to construction");
      }

      // Completeness — top-level only, strict factories only, and only when the runtime itself
      // would be strict (no permissive telescope/constant/compute rows) and every row was visible.
      final var srcProps = propertiesOf(srcType, srcEl);
      final var tgtProps = propertiesOf(tgtType, tgtEl);
      if (strict && analyzable && !permissive) {
        final var match = PairingRules.matchFields(
          List.copyOf(srcProps.keySet()),
          List.copyOf(tgtProps.keySet()),
          claimedSrc,
          claimedTgt
        );
        final var srcSlot = srcEl.getKind() == ElementKind.RECORD ? "field" : "property";
        final var tgtSlot = tgtEl.getKind() == ElementKind.RECORD ? "field" : "property";
        for (final var name : match.unmatchedTargets()) {
          report(node, PairingMessages.noSameNameSource(srcSimple, tgtSimple, tgtSlot, srcSlot, name));
        }
        for (final var name : match.unmatchedSources()) {
          report(node, PairingMessages.noSameNameTarget(srcSimple, tgtSimple, srcSlot, tgtSlot, name));
        }
        final var seen = new HashSet<String>();
        seen.add(pairKey(srcType, tgtType));
        for (final var name : match.matched()) {
          verifyDeep(srcProps.get(name), tgtProps.get(name), name, node, seen, false);
        }
      }
      verifyRowsVisited(node, args, srcType, tgtType, srcEl, tgtEl, strict);
    }

    /**
     * The two classes a {@code cases} row pairs, after reporting either one that is not a permitted
     * subclass of a sealed interface, as the row's factory refuses it; null when either is not a
     * class literal or is refused.
     */
    private TypeElement[] caseRow(final MethodInvocationTree row) {
      final var rowArgs = row.getArguments();
      if (rowArgs.size() != 2) return null;
      final var source = classLiteral(rowArgs.get(0));
      final var target = classLiteral(rowArgs.get(1));
      final var sourceEl = source == null ? null : props.elementOf(source);
      final var targetEl = target == null ? null : props.elementOf(target);
      if (sourceEl == null || targetEl == null) return null;
      final var rendered = PairingMessages.caseRow(
        sourceEl.getSimpleName().toString(),
        targetEl.getSimpleName().toString()
      );
      for (final var named : List.of(sourceEl, targetEl)) {
        if (sealedRootsOf(named).isEmpty()) {
          report(row, PairingMessages.notACase(rendered, named.getSimpleName().toString()));
          return null;
        }
      }
      return new TypeElement[] { sourceEl, targetEl };
    }

    /**
     * The sealed interfaces {@code type} implements that name it among their permitted subclasses.
     */
    private List<TypeElement> sealedRootsOf(final TypeElement type) {
      final var roots = new ArrayList<TypeElement>();
      for (final var implemented : type.getInterfaces()) {
        final var root = props.elementOf(implemented);
        if (root != null && permits(root, type)) roots.add(root);
      }
      return roots;
    }

    /**
     * Whether the sealed interface {@code root} names {@code type} among its permitted subclasses.
     */
    private boolean permits(final TypeElement root, final TypeElement type) {
      if (!root.getModifiers().contains(Modifier.SEALED)) return false;
      for (final var permitted : root.getPermittedSubclasses()) {
        if (types.isSameType(types.erasure(permitted), types.erasure(type.asType()))) return true;
      }
      return false;
    }

    /**
     * A call mapping two sealed interfaces: reports the {@code cases} rows construction refuses
     * whatever bridges it finds, which are a class no sealed interface permits and two rows pairing
     * the same case of the call's own pair.
     */
    private void verifySealedCall(
      final List<? extends ExpressionTree> args,
      final TypeElement srcEl,
      final TypeElement tgtEl
    ) {
      final var paired = new HashSet<String>();
      for (var i = 2; i < args.size(); i++) {
        if (!(args.get(i) instanceof MethodInvocationTree row)) continue;
        if (
          !(elementAt(row.getMethodSelect()) instanceof ExecutableElement rowExec) ||
          !(rowExec.getEnclosingElement() instanceof TypeElement rowOwner) ||
          !rowOwner.getQualifiedName().contentEquals(CASES_FQN)
        ) {
          continue;
        }
        final var cases = caseRow(row);
        if (cases == null || !permits(srcEl, cases[0]) || !permits(tgtEl, cases[1])) continue;
        if (!paired.add(binaryName(cases[0]))) {
          report(
            row,
            PairingMessages.caseRowPairedTwice(
              srcEl.getSimpleName().toString(),
              tgtEl.getSimpleName().toString(),
              cases[0].getSimpleName().toString()
            )
          );
        }
      }
    }

    /**
     * Reports the rows keyed to a type pair the mapper never visits, with the message construction
     * refuses them with. Construction keys each row to the classes its accessors are applied to,
     * and to the call's own pair for a side given as a telescope path, for a {@code constant},
     * {@code compute} or {@code zip} row, and for a target accessor applied to a type the call's
     * target extends. A {@code when} row is keyed as the row it wraps. Construction then visits the
     * call's pair and every pair reached from it, and refuses the rows of any other pair, {@code
     * drop} rows aside.
     *
     * <p>Nothing is reported unless every row can be keyed and the whole walk can be followed: a
     * row this cannot read could claim a property or reach a pair, and a property typed by a type
     * variable, a pair the shared decision refuses, or a forward-only row on a strict factory each
     * make construction answer before it gets here or differently from this walk.
     */
    private void verifyRowsVisited(
      final MethodInvocationTree node,
      final List<? extends ExpressionTree> args,
      final TypeMirror srcType,
      final TypeMirror tgtType,
      final TypeElement srcEl,
      final TypeElement tgtEl,
      final boolean strict
    ) {
      final var table = new LinkedHashMap<String, List<KeyedRow>>();
      final var caseRows = new ArrayList<String>();
      Tree firstCaseRow = null;
      var caseRowsNamed = true;
      var writeHinted = false;
      for (var i = 2; i < args.size(); i++) {
        if (!(args.get(i) instanceof MethodInvocationTree row)) return;
        if (
          !(elementAt(row.getMethodSelect()) instanceof ExecutableElement rowExec) ||
          !(rowExec.getEnclosingElement() instanceof TypeElement rowOwner)
        ) {
          return;
        }
        final var ownerFqn = rowOwner.getQualifiedName().toString();
        if (WRITE_HINT_FQN.equals(ownerFqn)) {
          writeHinted = true;
          continue;
        }
        if (NULL_HINT_FQN.equals(ownerFqn)) continue;
        if (CASES_FQN.equals(ownerFqn)) {
          final var rowArgs = row.getArguments();
          final var source = rowArgs.size() == 2 ? classLiteral(rowArgs.get(0)) : null;
          final var target = rowArgs.size() == 2 ? classLiteral(rowArgs.get(1)) : null;
          final var sourceEl = source == null ? null : props.elementOf(source);
          final var targetEl = target == null ? null : props.elementOf(target);
          // A row naming a class no sealed interface permits is refused by its factory, first.
          if (
            sourceEl == null ||
            targetEl == null ||
            sealedRootsOf(sourceEl).isEmpty() ||
            sealedRootsOf(targetEl).isEmpty()
          ) caseRowsNamed = false;
          else caseRows.add(
            PairingMessages.caseRow(sourceEl.getSimpleName().toString(), targetEl.getSimpleName().toString())
          );
          if (firstCaseRow == null) firstCaseRow = row;
          continue;
        }
        if (!MAPPING_FQN.equals(ownerFqn)) return;
        final var keyed = keyedRow(row, rowExec, srcEl, tgtEl, strict);
        if (keyed == null) return;
        table.computeIfAbsent(pairKey(keyed.source(), keyed.target()), __ -> new ArrayList<>()).add(keyed);
      }
      final var visited = new HashSet<String>();
      if (!visit(srcType, tgtType, table, visited, !strict, null)) return;
      // A write hint picks the writer a bean is built with and keeps its pairs from their own
      // bridges, so the setters such a call writes through are left to construction. A
      // mapperForward call given no rows converts through its own pair's bridge where there is one.
      if (!writeHinted) verifySetters(node, srcType, tgtType, table, !strict, !strict && args.size() == 2);
      // The whole walk was followed and met no pair of sealed interfaces,
      // and only such a pair takes a cases row.
      if (!caseRows.isEmpty() && caseRowsNamed) {
        report(
          firstCaseRow,
          PairingMessages.caseRowsNeverDispatched(
            srcEl.getSimpleName().toString(),
            tgtEl.getSimpleName().toString(),
            caseRows
          )
        );
      }
      final var dead = new ArrayList<String>();
      Tree at = null;
      for (final var bucket : table.entrySet()) {
        if (visited.contains(bucket.getKey())) continue;
        final var rows = new ArrayList<String>();
        for (final var row : bucket.getValue()) {
          if (row.kind() == RowKind.DROP) continue;
          rows.add(PairingMessages.unvisitedRow(row.sourceMethod(), row.targetMethod()));
          if (at == null) at = row.at();
        }
        if (rows.isEmpty()) continue;
        final var first = bucket.getValue().getFirst();
        dead.add(PairingMessages.unvisitedPair(binaryName(first.source()), binaryName(first.target()), rows));
      }
      if (dead.isEmpty()) return;
      report(at, PairingMessages.rowsNeverVisited(binaryName(srcEl), binaryName(tgtEl), dead));
    }

    /**
     * Reports each bean property construction refuses to fill because its setter cannot take a
     * value of the property's own type, with the message construction refuses it with. On every
     * pair construction walks, a property is filled from the other side when a row other than
     * {@code drop} names it on both sides or when no such row names it and both sides have it, a
     * row that converts the value or a path row writing the property included. Construction checks
     * the target's setters for each, and the source's too when the mapper also runs backward.
     *
     * <p>A side is checked only when construction is certain to build it through its setters, which
     * the shared write strategy decides once the bean offers no static {@code builder()}, no public
     * constructor taking as many arguments as it has properties, and a public constructor taking
     * none; a generic bean is left to construction. A pair below the call's own is walked only when
     * no bridge of its own can stand in for the walk: a row is keyed to it, or {@link
     * #bridgeVisible} finds none. {@code rootBridgeable} holds for a call whose own pair a bridge
     * of its own may convert, which the same test then decides. A bridge a carrier declares in a
     * module compiled apart from this one is registered only as a service, which nothing here can
     * read.
     */
    private void verifySetters(
      final MethodInvocationTree node,
      final TypeMirror srcType,
      final TypeMirror tgtType,
      final Map<String, List<KeyedRow>> table,
      final boolean forwardOnly,
      final boolean rootBridgeable
    ) {
      final var refusals = new LinkedHashSet<String>();
      final var visitor = new PairVisitor() {
        @Override
        public boolean walks(
          final TypeElement source,
          final TypeElement target,
          final boolean keyed,
          final boolean root
        ) {
          if (root && !rootBridgeable) return true;
          return keyed || !bridgeVisible(source, target);
        }

        @Override
        public void fills(
          final TypeElement source,
          final TypeElement target,
          final PairSides sides,
          final String sourceName,
          final String targetName
        ) {
          addRefusal(refusals, target, sides.target(), targetName);
          if (!forwardOnly) addRefusal(refusals, source, sides.source(), sourceName);
        }
      };
      if (!visit(srcType, tgtType, table, new HashSet<>(), forwardOnly, visitor)) return;
      for (final var refusal : refusals) report(node, refusal);
    }

    /** Adds construction's refusal of a write of {@code name} on {@code bean}, if it has one. */
    private void addRefusal(
      final Set<String> refusals,
      final TypeElement bean,
      final Map<String, TypeMirror> properties,
      final String name
    ) {
      final var read = properties.get(name);
      if (read == null || !builtThroughSetters(bean, properties)) return;
      if (props.mentionsTypeVariable(read)) return;
      final var refusal = setters.refusal(bean, name, read, MirrorSetters.Write.REFLECTIVE);
      if (refusal != null) refusals.add(refusal);
    }

    /**
     * Whether construction certainly builds {@code bean}, whose properties are {@code properties},
     * through a public no-argument constructor and its setters.
     */
    private boolean builtThroughSetters(final TypeElement bean, final Map<String, TypeMirror> properties) {
      if (bean.getKind() != ElementKind.CLASS || bean.getModifiers().contains(Modifier.ABSTRACT)) return false;
      if (!bean.getTypeParameters().isEmpty()) return false;
      // An inner class's constructors take the enclosing instance, so none takes no arguments.
      final var nesting = bean.getNestingKind();
      if (
        nesting != NestingKind.TOP_LEVEL &&
        !(nesting == NestingKind.MEMBER && bean.getModifiers().contains(Modifier.STATIC))
      ) {
        return false;
      }
      final var mayBuild = ElementFilter.methodsIn(elements.getAllMembers(bean))
        .stream()
        .anyMatch(m -> m.getSimpleName().contentEquals("builder") && m.getModifiers().contains(Modifier.STATIC));
      var noArgs = false;
      var takesAll = false;
      for (final var ctor : ElementFilter.constructorsIn(bean.getEnclosedElements())) {
        if (!ctor.getModifiers().contains(Modifier.PUBLIC)) continue;
        if (ctor.getParameters().isEmpty()) noArgs = true;
        if (ctor.getParameters().size() == properties.size()) takesAll = true;
      }
      if (mayBuild || takesAll || !noArgs) return false;
      final var names = List.copyOf(properties.keySet());
      final var strategy = BeanWriteStrategy.auto(
        new BeanWriteStrategy.Shape() {
          @Override
          public List<String> properties() {
            return names;
          }

          @Override
          public boolean hasBuilder() {
            return false;
          }

          @Override
          public BeanWriteStrategy.Member builderMember(final String property) {
            return BeanWriteStrategy.Member.ABSENT;
          }

          @Override
          public boolean hasConstructor() {
            return false;
          }

          @Override
          public boolean hasSetters() {
            return true;
          }

          @Override
          public boolean setterWrites(final String property) {
            return true;
          }

          @Override
          public boolean stored(final String property) {
            return true;
          }
        }
      );
      return strategy.orElse(null) == BeanWriteStrategy.SETTERS;
    }

    /**
     * The pair construction keys {@code row} to and what the row does there, or null when that
     * cannot be read from the call: an accessor that is no method reference, a factory this does
     * not know, or a forward-only row on a strict factory, which construction refuses first.
     */
    private KeyedRow keyedRow(
      final MethodInvocationTree row,
      final ExecutableElement rowExec,
      final TypeElement srcEl,
      final TypeElement tgtEl,
      final boolean strict
    ) {
      final var rowArgs = row.getArguments();
      switch (rowExec.getSimpleName().toString()) {
        case "to" -> {
          if (rowArgs.size() == 4) return accessorRow(row, RowKind.CONVERTING);
          if (rowArgs.size() != 2) return null;
          final var srcPath = isTelescopeParameter(rowExec, 0);
          final var tgtPath = isTelescopeParameter(rowExec, 1);
          if (!srcPath && !tgtPath) return accessorRow(row, RowKind.PASS_THROUGH);
          if (srcPath && tgtPath) return new KeyedRow(row, srcEl, tgtEl, RowKind.PATH, null, null);
          if (!srcPath) {
            final var src = accessorProp(rowArgs.get(0));
            return src == null ? null : new KeyedRow(row, src.owner(), tgtEl, RowKind.PATH, src.method(), null);
          }
          final var tgt = accessorProp(rowArgs.get(1));
          if (tgt == null) return null;
          final var onTarget = types.isSubtype(types.erasure(tgtEl.asType()), types.erasure(tgt.owner().asType()));
          return new KeyedRow(row, srcEl, onTarget ? tgtEl : tgt.owner(), RowKind.PATH, null, tgt.method());
        }
        case "toOneWay" -> {
          return strict ? null : accessorRow(row, RowKind.CONVERTING);
        }
        case "enumTo", "toOrElse", "toOrElseGet", "via" -> {
          return accessorRow(row, RowKind.CONVERTING);
        }
        case "drop" -> {
          final var src = accessorProp(rowArgs.isEmpty() ? null : rowArgs.get(0));
          if (src == null) return null;
          if (rowArgs.size() == 1) return new KeyedRow(row, src.owner(), tgtEl, RowKind.DROP, src.method(), null);
          final var explicit = rowArgs.size() == 2 ? classLiteral(rowArgs.get(1)) : null;
          final var explicitEl = explicit == null ? null : props.elementOf(explicit);
          return explicitEl == null
            ? null
            : new KeyedRow(row, src.owner(), explicitEl, RowKind.DROP, src.method(), null);
        }
        case "when" -> {
          return whenRow(row, srcEl, tgtEl, strict);
        }
        case "constant", "compute", "zip" -> {
          return new KeyedRow(row, srcEl, tgtEl, RowKind.PATH, null, null);
        }
        default -> {
          return null;
        }
      }
    }

    /**
     * A {@code when(predicate, inner)} row, keyed exactly as {@code inner} is, or null when {@code
     * inner} cannot be read or is a row {@code when} refuses to wrap: one converting a property
     * through accessors on both sides, a {@code drop}, or another {@code when}.
     */
    private KeyedRow whenRow(
      final MethodInvocationTree row,
      final TypeElement srcEl,
      final TypeElement tgtEl,
      final boolean strict
    ) {
      final var rowArgs = row.getArguments();
      if (rowArgs.size() != 2 || !(rowArgs.get(1) instanceof MethodInvocationTree inner)) return null;
      if (
        !(elementAt(inner.getMethodSelect()) instanceof ExecutableElement innerExec) ||
        !(innerExec.getEnclosingElement() instanceof TypeElement innerOwner) ||
        !innerOwner.getQualifiedName().contentEquals(MAPPING_FQN) ||
        innerExec.getSimpleName().contentEquals("when")
      ) {
        return null;
      }
      final var keyed = keyedRow(inner, innerExec, srcEl, tgtEl, strict);
      if (keyed == null || keyed.kind() != RowKind.PATH) return null;
      return new KeyedRow(
        row,
        keyed.source(),
        keyed.target(),
        RowKind.PATH,
        keyed.sourceMethod(),
        keyed.targetMethod()
      );
    }

    /** A row given two accessors, keyed to the classes they are applied to, or null. */
    private KeyedRow accessorRow(final MethodInvocationTree row, final RowKind kind) {
      final var rowArgs = row.getArguments();
      final var src = accessorProp(rowArgs.get(0));
      final var tgt = accessorProp(rowArgs.get(1));
      if (src == null || tgt == null) return null;
      return new KeyedRow(row, src.owner(), tgt.owner(), kind, src.method(), tgt.method());
    }

    /**
     * Whether the factory's parameter at {@code index} is a telescope path rather than an accessor.
     */
    private boolean isTelescopeParameter(final ExecutableElement factory, final int index) {
      final var parameter = types.erasure(factory.getParameters().get(index).asType());
      return (
        types.asElement(parameter) instanceof TypeElement type && type.getQualifiedName().contentEquals(TELESCOPE_FQN)
      );
    }

    /**
     * Adds to {@code visited} the pair {@code (src, tgt)} and every pair construction reaches from
     * it: through each property the pair's own rows pass through unchanged, and through each
     * same-named property no row of the pair converts, along the shared pair decision's descent
     * into nested pairs, {@code Optional} bridges and container elements. False when the walk
     * cannot be followed as construction follows it. A non-null {@code visitor} is told of each
     * property construction fills, and a pair it says construction does not walk is neither added
     * nor followed.
     */
    private boolean visit(
      final TypeMirror src,
      final TypeMirror tgt,
      final Map<String, List<KeyedRow>> table,
      final Set<String> visited,
      final boolean forwardOnly,
      final PairVisitor visitor
    ) {
      final var srcEl = props.elementOf(src);
      final var tgtEl = props.elementOf(tgt);
      if (srcEl == null || tgtEl == null) return false;
      final var key = pairKey(srcEl, tgtEl);
      if (visitor != null && !visitor.walks(srcEl, tgtEl, table.containsKey(key), visited.isEmpty())) return true;
      if (!visited.add(key)) return true;
      final var sides = new PairSides(
        propertiesOf(src, srcEl),
        propertiesOf(tgt, tgtEl),
        declaredPropertiesOf(srcEl),
        declaredPropertiesOf(tgtEl)
      );
      final var converted = new HashSet<String>();
      for (final var row : table.getOrDefault(key, List.of())) {
        if (row.kind() != RowKind.PASS_THROUGH && row.kind() != RowKind.CONVERTING) continue;
        final var tgtName = propertyName(row.targetMethod(), tgtEl);
        final var srcName = propertyName(row.sourceMethod(), srcEl);
        converted.add(tgtName);
        if (visitor != null) visitor.fills(srcEl, tgtEl, sides, srcName, tgtName);
        if (row.kind() == RowKind.CONVERTING) continue;
        if (!visitProperty(sides, srcName, tgtName, table, visited, forwardOnly, visitor)) return false;
      }
      for (final var name : sides.target().keySet()) {
        if (converted.contains(name) || !sides.source().containsKey(name)) continue;
        if (visitor != null) visitor.fills(srcEl, tgtEl, sides, name, name);
        if (!visitProperty(sides, name, name, table, visited, forwardOnly, visitor)) return false;
      }
      return true;
    }

    private boolean visitProperty(
      final PairSides sides,
      final String srcName,
      final String tgtName,
      final Map<String, List<KeyedRow>> table,
      final Set<String> visited,
      final boolean forwardOnly,
      final PairVisitor visitor
    ) {
      final var srcType = sides.source().get(srcName);
      final var tgtType = sides.target().get(tgtName);
      if (srcType == null || tgtType == null) return false;
      // Construction reads such a property as declared or as a member depending on the pair, and
      // recurses on the raw class; a property typed by a variable is left to it.
      if (
        Stream.of(srcType, tgtType, sides.declaredSource().get(srcName), sides.declaredTarget().get(tgtName)).anyMatch(
          props::mentionsTypeVariable
        )
      ) {
        return false;
      }
      return visitTypes(srcType, tgtType, srcName, table, visited, forwardOnly, visitor);
    }

    /**
     * Follows the shared decision for one pair of types that name no type variable. The element
     * types a decision descends to are type arguments of those types, or of the supertypes they
     * parameterize, so they name none either.
     */
    private boolean visitTypes(
      final TypeMirror srcType,
      final TypeMirror tgtType,
      final String componentName,
      final Map<String, List<KeyedRow>> table,
      final Set<String> visited,
      final boolean forwardOnly,
      final PairVisitor visitor
    ) {
      final var decision = rules.decidePair(srcType, tgtType, componentName, forwardOnly);
      return switch (decision) {
        case PairDecision.RecursePair<TypeMirror> __ -> visit(srcType, tgtType, table, visited, forwardOnly, visitor);
        case PairDecision.OptionalToNullable<TypeMirror> d -> visitTypes(
          d.elementSrc(),
          d.elementTgt(),
          componentName,
          table,
          visited,
          forwardOnly,
          visitor
        );
        case PairDecision.NullableToOptional<TypeMirror> d -> visitTypes(
          d.elementSrc(),
          d.elementTgt(),
          componentName,
          table,
          visited,
          forwardOnly,
          visitor
        );
        case PairDecision.LiftContainer<TypeMirror> d -> visitTypes(
          d.src().elementType(),
          d.tgt().elementType(),
          componentName,
          table,
          visited,
          forwardOnly,
          visitor
        );
        // Which case pairs with which depends on bridges construction finds, so the pairs the cases
        // reach are left to it.
        case PairDecision.DispatchCases<TypeMirror> __ -> false;
        case PairDecision.Incompatible<TypeMirror> __ -> false;
        default -> true;
      };
    }

    /** Returns false when the row's accessors defeat static analysis (kills completeness only). */
    private boolean verifyRow(
      final MethodInvocationTree row,
      final ExecutableElement rowExec,
      final TypeMirror srcType,
      final TypeMirror tgtType,
      final String srcSimple,
      final String tgtSimple,
      final Set<String> claimedSrc,
      final Set<String> claimedTgt,
      final boolean forwardOnly
    ) {
      final var rowName = rowExec.getSimpleName().toString();
      final var rowArgs = row.getArguments();
      switch (rowName) {
        case "to", "toOrElse", "toOrElseGet", "toOneWay", "enumTo", "via" -> {
          final var src = accessorProp(rowArgs.isEmpty() ? null : rowArgs.get(0));
          final var tgt = rowArgs.size() < 2 ? null : accessorProp(rowArgs.get(1));
          if ("to".equals(rowName) && rowArgs.size() == 2 && isTelescopeParameter(rowExec, 0)) {
            verifySourcePath(row, rowName, rowArgs.get(0), srcType, srcSimple, tgtSimple);
          }
          if ("to".equals(rowName) && rowArgs.size() == 2 && tgt == null) {
            verifyTargetPath(row, rowName, rowArgs.get(1), tgtType, srcSimple, tgtSimple);
          }
          if (src == null || tgt == null) return false;
          // Construction groups rows by their accessors' receiver classes, so a row may be keyed to
          // a nested pair the recursion reaches. Only rows keyed to this call's pair claim fields
          // here; nested-keyed rows are still shape-checked against their own types below. Both
          // sides are erased: a generic owner's asType() is the parameterized prototype, which
          // isSameType never matches against the raw class literal.
          final var topLevelRow =
            types.isSameType(types.erasure(src.owner().asType()), types.erasure(srcType)) &&
            types.isSameType(types.erasure(tgt.owner().asType()), types.erasure(tgtType));
          if (topLevelRow) {
            // Always consume the source side — even when the target is a duplicate — so the source
            // field isn't later reported as unmatched (which would be a cascade error on top of the
            // real duplicate-target diagnostic).
            claimedSrc.add(src.name());
            if (!claimedTgt.add(tgt.name())) {
              report(row, PairingMessages.duplicateTargetRow(srcSimple, tgtSimple, tgt.name()));
              return true;
            }
          }
          // Only the 2-arg same-typed to(src, tgt) carries no conversion — the one row shape the
          // runtime itself routes through the pairing decision at construction. Every other form
          // carries user functions (or a user default), which construction accepts as-is.
          if ("to".equals(rowName) && rowArgs.size() == 2) {
            final var seen = new HashSet<String>();
            verifyDeep(src.type(), tgt.type(), src.name() + " → " + tgt.name(), row, seen, forwardOnly);
          }
          return true;
        }
        case "drop" -> {
          final var src = accessorProp(rowArgs.isEmpty() ? null : rowArgs.get(0));
          if (src == null) return false;
          if (
            types.isSameType(types.erasure(src.owner().asType()), types.erasure(srcType)) && !claimedSrc.add(src.name())
          ) {
            report(row, PairingMessages.duplicateSourceRow(srcSimple, tgtSimple, src.name()));
          }
          return true;
        }
        case "when" -> {
          // Conditional wrapper: the predicate is opaque; the inner row is the real claim.
          if (rowArgs.size() == 2 && rowArgs.get(1) instanceof MethodInvocationTree inner) {
            final var innerMethod = elementAt(inner.getMethodSelect());
            if (innerMethod instanceof ExecutableElement innerExec) {
              return verifyRow(
                inner,
                innerExec,
                srcType,
                tgtType,
                srcSimple,
                tgtSimple,
                claimedSrc,
                claimedTgt,
                forwardOnly
              );
            }
          }
          return false;
        }
        case "constant", "compute" -> {
          // These rows put the runtime into permissive mode (handled by the caller), which disables
          // completeness, so they claim nothing. They always write on the call's target, so the
          // accessor must name a property of the target or of a type it extends, and an inline
          // target telescope must be one construction can start at the target.
          final var arg = rowArgs.isEmpty() ? null : rowArgs.get(0);
          final var tgt = accessorProp(arg);
          if (tgt != null) verifyWrittenOnTarget(row, rowName, tgt, tgtType, srcSimple, tgtSimple);
          else verifyTargetPath(row, rowName, arg, tgtType, srcSimple, tgtSimple);
          return true;
        }
        case "zip" -> {
          verifySourcePath(row, rowName, rowArgs.get(0), srcType, srcSimple, tgtSimple);
          verifyTargetPath(row, rowName, rowArgs.get(1), tgtType, srcSimple, tgtSimple);
          return false;
        }
        default -> {
          return false;
        }
      }
    }

    /**
     * {@code constant} / {@code compute} switch the runtime into its permissive fixup mode — and a
     * {@code when(...)} wrapper is peeled to its inner row at construction, so a wrapped {@code
     * constant}/{@code compute} is exactly as permissive as a bare one.
     */
    private boolean isPermissiveRow(final MethodInvocationTree row, final ExecutableElement rowExec) {
      final var n = rowExec.getSimpleName().toString();
      if ("constant".equals(n) || "compute".equals(n)) return true;
      if (
        "when".equals(n) &&
        row.getArguments().size() == 2 &&
        row.getArguments().get(1) instanceof MethodInvocationTree inner
      ) {
        return (
          elementAt(inner.getMethodSelect()) instanceof ExecutableElement innerExec && isPermissiveRow(inner, innerExec)
        );
      }
      return false;
    }

    private void verifyWriteHint(
      final MethodInvocationTree row,
      final ExecutableElement rowExec,
      final Set<String> hintTargets
    ) {
      if (!"writeBean".contentEquals(rowExec.getSimpleName())) return;
      final var target = row.getArguments().isEmpty() ? null : classLiteral(row.getArguments().getFirst());
      final var targetEl = target == null ? null : props.elementOf(target);
      if (targetEl == null) {
        note(
          row,
          "telescope: writeBean hint not statically verifiable (non-literal class argument); deferring to construction"
        );
        return;
      }
      if (targetEl.getKind() == ElementKind.RECORD) {
        report(row, PairingMessages.writeBeanTargetsRecord(elements.getBinaryName(targetEl).toString()));
        return;
      }
      final var binaryName = elements.getBinaryName(targetEl).toString();
      if (!hintTargets.add(binaryName)) {
        report(row, PairingMessages.duplicateWriteBeanHint(binaryName));
      }
    }

    /**
     * Replay the shared pair decision for one field pair, recursing exactly as construction does:
     * container lifts and Optional bridges recurse on elements; reflectable pairs recurse on their
     * same-name matches (nested pairs are lenient about unmatched fields, as at runtime, but each
     * matched nested field is still shape-checked). Cycles terminate via {@code seen}. {@code
     * forwardOnly} holds for a {@code mapperForward} site, whose enum pairs need a counterpart for
     * each source constant only.
     */
    private void verifyDeep(
      final TypeMirror srcType,
      final TypeMirror tgtType,
      final String componentName,
      final Tree at,
      final Set<String> seen,
      final boolean forwardOnly
    ) {
      // A type variable is bound only when the mapper is built, so nothing here can say what it
      // pairs with. Skip rather than mis-decide: the construction backstop still applies.
      if (!staticallyComparable(srcType) || !staticallyComparable(tgtType)) return;
      final var decision = rules.decidePair(srcType, tgtType, componentName, forwardOnly);
      if (decision instanceof PairDecision.Incompatible<TypeMirror> incompatible) {
        report(at, incompatible.message());
        return;
      }
      if (decision instanceof PairDecision.RecursePair) {
        if (!seen.add(pairKey(srcType, tgtType))) return;
        final var srcEl = props.elementOf(srcType);
        final var tgtEl = props.elementOf(tgtType);
        if (srcEl == null || tgtEl == null) return;
        final var srcProps = propertiesOf(srcType, srcEl);
        final var tgtProps = propertiesOf(tgtType, tgtEl);
        final var match = PairingRules.matchFields(
          List.copyOf(srcProps.keySet()),
          List.copyOf(tgtProps.keySet()),
          Set.of(),
          Set.of()
        );
        for (final var name : match.matched()) {
          verifyDeep(srcProps.get(name), tgtProps.get(name), name, at, seen, forwardOnly);
        }
        return;
      }
      if (decision instanceof PairDecision.OptionalToNullable<TypeMirror> d) {
        verifyDeep(d.elementSrc(), d.elementTgt(), componentName + "[*]", at, seen, forwardOnly);
        return;
      }
      if (decision instanceof PairDecision.NullableToOptional<TypeMirror> d) {
        verifyDeep(d.elementSrc(), d.elementTgt(), componentName + "[*]", at, seen, forwardOnly);
        return;
      }
      if (decision instanceof PairDecision.LiftContainer<TypeMirror> d) {
        final var refusal = unbuildableSide(srcType, tgtType, d, componentName, forwardOnly);
        if (refusal != null) {
          report(at, refusal);
          return;
        }
        verifyDeep(d.src().elementType(), d.tgt().elementType(), componentName + "[*]", at, seen, forwardOnly);
      }
    }

    /**
     * The allocation refusal construction raises for a lifted collection or map whose elements
     * convert, or null. Construction builds an allocator for both sides whichever directions the
     * mapper runs, and falls back to a public static {@code builder()} taking no arguments where
     * the shared allocation rules refuse, so a side offering one is left to construction.
     */
    private String unbuildableSide(
      final TypeMirror srcType,
      final TypeMirror tgtType,
      final PairDecision.LiftContainer<TypeMirror> lift,
      final String componentName,
      final boolean forwardOnly
    ) {
      final var kind = lift.src().kind();
      if (kind == ContainerView.Kind.OPTIONAL) return null;
      final var elements = rules.decidePair(
        lift.src().elementType(),
        lift.tgt().elementType(),
        componentName,
        forwardOnly
      );
      if (elements instanceof PairDecision.Identity || elements instanceof PairDecision.Incompatible) return null;
      for (final var side : List.of(srcType, tgtType)) {
        if (allocation.allocate(side, kind, null) instanceof Allocation.Refuse refuse && !declaresStaticBuilder(side)) {
          return refuse.reason();
        }
      }
      return null;
    }

    /**
     * Whether {@code type}'s class has a public static {@code builder()} taking no arguments,
     * declared or inherited, which is the one shape construction falls back to.
     */
    private boolean declaresStaticBuilder(final TypeMirror type) {
      final var element = props.elementOf(type);
      if (element == null) return true;
      return ElementFilter.methodsIn(elements.getAllMembers(element))
        .stream()
        .anyMatch(
          method ->
            method.getSimpleName().contentEquals("builder") &&
            method.getParameters().isEmpty() &&
            method.getModifiers().contains(Modifier.STATIC) &&
            method.getModifiers().contains(Modifier.PUBLIC)
        );
    }

    /** True when {@code t} contains no type variable at any depth, wildcard bounds included. */
    private boolean staticallyComparable(final TypeMirror t) {
      if (t.getKind() == TypeKind.TYPEVAR) return false;
      if (t instanceof WildcardType wildcard) {
        return Stream.of(wildcard.getExtendsBound(), wildcard.getSuperBound())
          .filter(Objects::nonNull)
          .allMatch(this::staticallyComparable);
      }
      if (t instanceof ArrayType at) return staticallyComparable(at.getComponentType());
      if (t instanceof DeclaredType dt) {
        for (final var arg : dt.getTypeArguments()) {
          if (!staticallyComparable(arg)) return false;
        }
      }
      return true;
    }

    /** The {@code TypeMirror} behind a class-literal argument ({@code Order.class}), or null. */
    private TypeMirror classLiteral(final ExpressionTree arg) {
      if (!(arg instanceof MemberSelectTree select) || !select.getIdentifier().contentEquals("class")) return null;
      final var path = TreePath.getPath(getCurrentPath().getCompilationUnit(), select.getExpression());
      return path == null ? null : trees.getTypeMirror(path);
    }

    /**
     * Reports a row that writes the call's target through {@code prop} when {@code prop} belongs to
     * a type the target neither is nor extends; construction refuses the same row with the same
     * message. A null {@code prop} is not statically known and is left to construction.
     */
    private void verifyWrittenOnTarget(
      final MethodInvocationTree row,
      final String rowName,
      final Prop prop,
      final TypeMirror tgtType,
      final String srcSimple,
      final String tgtSimple
    ) {
      if (prop == null) return;
      if (types.isSubtype(types.erasure(tgtType), types.erasure(prop.owner().asType()))) return;
      report(
        row,
        PairingMessages.targetRowOffTarget(
          srcSimple,
          tgtSimple,
          rowName,
          prop.owner().getSimpleName().toString(),
          prop.name()
        )
      );
    }

    /**
     * Reports an inline target telescope that construction is certain to refuse, with the message
     * it refuses it with, and says nothing about any shape this does not model. The path is {@code
     * Telescope.of(X.class)} or {@code Telescope.ofBean(X.class)} followed by navigation calls, or
     * {@code Telescope.lens(getter, setter)} with a method-reference getter. Calls that keep the
     * focus on the root value come first: {@code filter} and {@code observe}, which pass, and
     * {@code as(Y.class)}, which is reported unless the target is {@code Y} or extends it. The
     * first call after them decides the rest. An accessor hop must name a property of the target or
     * of a type it extends, and {@code fieldByName} reads its property on whatever class it is
     * given. The end of the chain, a write of the root value, and a lens root are reported only
     * when the root's class is neither a supertype nor a subtype of the target: a path rooted at
     * either may still write the target, which construction checks on each write. Any other call,
     * such as {@code then}, ends the check without a report.
     */
    private void verifyTargetPath(
      final MethodInvocationTree row,
      final String rowName,
      final ExpressionTree arg,
      final TypeMirror tgtType,
      final String srcSimple,
      final String tgtSimple
    ) {
      final var calls = telescopeCalls(arg);
      if (calls == null) return;
      final var rootArgs = calls.getFirst().getArguments();
      final var rootName = factoryName(calls.getFirst());
      if ("lens".equals(rootName)) {
        final var getter = rootArgs.isEmpty() ? null : accessorProp(rootArgs.getFirst());
        if (getter != null) verifyRootRelated(row, rowName, getter.owner().asType(), tgtType, srcSimple, tgtSimple);
        return;
      }
      if (!"of".equals(rootName) && !"ofBean".equals(rootName)) return;
      final var focus = rootArgs.size() == 1 ? classLiteral(rootArgs.getFirst()) : null;
      for (final var call : calls.subList(1, calls.size())) {
        final var name = ((MemberSelectTree) call.getMethodSelect()).getIdentifier().toString();
        final var callArgs = call.getArguments();
        if ("filter".equals(name) || "observe".equals(name)) continue;
        if ("as".equals(name) && callArgs.size() == 1) {
          final var narrowed = classLiteral(callArgs.getFirst());
          if (narrowed == null) return;
          if (!types.isSubtype(types.erasure(tgtType), types.erasure(narrowed))) {
            report(
              row,
              PairingMessages.targetPathNarrowsOffTarget(srcSimple, tgtSimple, rowName, simpleName(narrowed))
            );
            return;
          }
          continue;
        }
        if (ROOT_HOPS.contains(name) && callArgs.size() == 1) {
          verifyWrittenOnTarget(row, rowName, accessorProp(callArgs.getFirst()), tgtType, srcSimple, tgtSimple);
        }
        return;
      }
      verifyRootRelated(row, rowName, focus, tgtType, srcSimple, tgtSimple);
    }

    /**
     * Reports a target path whose root class is neither a supertype nor a subtype of the call's
     * target, which construction refuses with the same message. A null {@code root} is not
     * statically known and is left to construction.
     */
    private void verifyRootRelated(
      final MethodInvocationTree row,
      final String rowName,
      final TypeMirror root,
      final TypeMirror tgtType,
      final String srcSimple,
      final String tgtSimple
    ) {
      if (root == null) return;
      final var rootErased = types.erasure(root);
      final var tgtErased = types.erasure(tgtType);
      if (types.isSubtype(tgtErased, rootErased) || types.isSubtype(rootErased, tgtErased)) return;
      report(row, PairingMessages.targetPathNotOnTarget(srcSimple, tgtSimple, rowName, simpleName(root)));
    }

    /**
     * The calls of an inline telescope chain, the factory first, when the chain starts at a static
     * factory of {@code Telescope}, or null for any other expression: a path held in a variable, or
     * one returned by a method of another class.
     */
    private List<MethodInvocationTree> telescopeCalls(final ExpressionTree arg) {
      final var calls = new ArrayList<MethodInvocationTree>();
      var current = arg;
      while (
        current instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select
      ) {
        calls.addFirst(call);
        current = select.getExpression();
      }
      if (calls.isEmpty()) return null;
      final var owner = (TypeElement) elementAt(calls.getFirst().getMethodSelect()).getEnclosingElement();
      return owner.getQualifiedName().contentEquals(TELESCOPE_FQN) ? calls : null;
    }

    /** The name of the method a call invokes, read from its resolved element. */
    private String factoryName(final MethodInvocationTree call) {
      return elementAt(call.getMethodSelect()).getSimpleName().toString();
    }

    /**
     * Reports an inline source telescope that construction is certain to refuse, with the message
     * it refuses it with, and says nothing about any shape this does not model. The path is {@code
     * Telescope.of(X.class)} or {@code Telescope.ofBean(X.class)} followed by navigation calls, or
     * {@code Telescope.lens(getter, setter)} with a method-reference getter. Calls that keep the
     * focus on the root value come first: {@code filter} and {@code observe}, which pass, and
     * {@code as(Y.class)}, which passes when the source is {@code Y} or extends it, is reported
     * when no source can be an instance of {@code Y}, and otherwise ends the check, since such a
     * path reads only a source of that class. The first call after them decides the rest. An
     * accessor hop must name a property of the source, of a type it extends, or of an interface a
     * source may implement, and {@code fieldByName} reads its property on whatever class it is
     * given. The end of the chain, a read of the root value, and a lens root are reported only when
     * no source can be an instance of the root's class: a path rooted at any other class may still
     * read the source, which construction checks on each call. Any other call, such as {@code
     * then}, ends the check without a report.
     */
    private void verifySourcePath(
      final MethodInvocationTree row,
      final String rowName,
      final ExpressionTree arg,
      final TypeMirror srcType,
      final String srcSimple,
      final String tgtSimple
    ) {
      final var calls = telescopeCalls(arg);
      if (calls == null) return;
      final var rootArgs = calls.getFirst().getArguments();
      final var rootName = factoryName(calls.getFirst());
      final var srcErased = types.erasure(srcType);
      if ("lens".equals(rootName)) {
        final var getter = accessorProp(rootArgs.getFirst());
        if (getter != null) verifySourceRootRelated(
          row,
          rowName,
          getter.owner().asType(),
          srcType,
          srcSimple,
          tgtSimple
        );
        return;
      }
      if (!"of".equals(rootName) && !"ofBean".equals(rootName)) return;
      for (final var call : calls.subList(1, calls.size())) {
        final var name = ((MemberSelectTree) call.getMethodSelect()).getIdentifier().toString();
        final var callArgs = call.getArguments();
        if ("filter".equals(name) || "observe".equals(name)) continue;
        if ("as".equals(name)) {
          final var narrowed = classLiteral(callArgs.getFirst());
          if (narrowed == null) return;
          final var narrowedErased = types.erasure(narrowed);
          if (types.isSubtype(srcErased, narrowedErased)) continue;
          if (!mayBeInstance(srcErased, narrowedErased)) {
            report(
              row,
              PairingMessages.sourcePathNarrowsOffSource(srcSimple, tgtSimple, rowName, simpleName(narrowed))
            );
          }
          return;
        }
        if (ROOT_HOPS.contains(name)) verifySourceComponent(
          row,
          rowName,
          accessorProp(callArgs.getFirst()),
          srcErased,
          srcSimple,
          tgtSimple
        );
        return;
      }
      verifySourceRootRelated(row, rowName, classLiteral(rootArgs.getFirst()), srcType, srcSimple, tgtSimple);
    }

    /**
     * Reports a source path's first component hop when construction refuses it with the same
     * message: one whose class is neither a supertype of the source nor an interface a source may
     * implement. A null {@code prop}, an accessor that is no method reference, is left to
     * construction.
     */
    private void verifySourceComponent(
      final MethodInvocationTree row,
      final String rowName,
      final Prop prop,
      final TypeMirror srcErased,
      final String srcSimple,
      final String tgtSimple
    ) {
      if (prop == null) return;
      final var ownerErased = types.erasure(prop.owner().asType());
      if (types.isSubtype(srcErased, ownerErased)) return;
      if (prop.owner().getKind().isInterface() && mayBeInstance(srcErased, ownerErased)) return;
      report(
        row,
        PairingMessages.sourceRowOffSource(
          srcSimple,
          tgtSimple,
          rowName,
          prop.owner().getSimpleName().toString(),
          prop.name()
        )
      );
    }

    /**
     * Reports a source path whose root class no instance of the call's source can be an instance
     * of, which construction refuses with the same message. A null {@code root} is not statically
     * known and is left to construction.
     */
    private void verifySourceRootRelated(
      final MethodInvocationTree row,
      final String rowName,
      final TypeMirror root,
      final TypeMirror srcType,
      final String srcSimple,
      final String tgtSimple
    ) {
      if (root == null) return;
      final var rootErased = types.erasure(root);
      final var srcErased = types.erasure(srcType);
      if (mayBeInstance(srcErased, rootErased)) return;
      report(row, PairingMessages.sourcePathNotOnSource(srcSimple, tgtSimple, rowName, simpleName(root)));
    }

    /**
     * Whether one object can be an instance of both {@code source} and {@code root}, as
     * construction decides it: one extends the other; either is sealed and one of its permitted
     * cases can be such an instance, since every instance of a sealed type is an instance of one of
     * its cases; or {@code root} is an interface and the source is a class that is not final, so a
     * subclass of it can implement that interface. The source of a call this verifies, and each
     * permitted case of it, is a class: never an interface or an enum.
     */
    private boolean mayBeInstance(final TypeMirror source, final TypeMirror root) {
      if (types.isSubtype(root, source) || types.isSubtype(source, root)) return true;
      final var sourceEl = (TypeElement) types.asElement(source);
      final var rootEl = (TypeElement) types.asElement(root);
      if (sourceEl.getModifiers().contains(Modifier.SEALED)) {
        return sourceEl
          .getPermittedSubclasses()
          .stream()
          .anyMatch(c -> mayBeInstance(types.erasure(c), root));
      }
      if (rootEl.getModifiers().contains(Modifier.SEALED)) {
        return rootEl
          .getPermittedSubclasses()
          .stream()
          .anyMatch(c -> mayBeInstance(source, types.erasure(c)));
      }
      return rootEl.getKind().isInterface() && !sourceEl.getModifiers().contains(Modifier.FINAL);
    }

    private String simpleName(final TypeMirror type) {
      final var element = types.asElement(type);
      return element == null ? type.toString() : element.getSimpleName().toString();
    }

    /** A method-reference accessor resolved to its normalized property name + declared type. */
    private Prop accessorProp(final ExpressionTree arg) {
      if (!(arg instanceof MemberReferenceTree)) return null;
      final var path = TreePath.getPath(getCurrentPath().getCompilationUnit(), arg);
      if (path == null) return null;
      if (!(trees.getElement(path) instanceof ExecutableElement accessor)) return null;
      if (!(accessor.getEnclosingElement() instanceof TypeElement declaring)) return null;
      final var method = accessor.getSimpleName().toString();
      final var name = normalize(method);
      // Resolve the return type against the accessor's OWN receiver — a row may be keyed to a
      // nested pair, so the call's top-level types are the wrong receiver for asMemberOf.
      final var receiver = receiverOf(path, declaring);
      if (receiver == null) return new Prop(name, accessor.getReturnType(), declaring, method);
      final var type = ((ExecutableType) types.asMemberOf(receiver, accessor)).getReturnType();
      return new Prop(name, type, (TypeElement) receiver.asElement(), method);
    }

    /**
     * The class a method-reference accessor is applied to: the first type argument of the {@code
     * Accessor<A, X>} it is passed as, which is the receiver the runtime reads from the reference's
     * instantiated method type. A getter inherited from a superclass is applied to the subclass the
     * row names, and the row keys against that subclass. A type variable stands for its bound,
     * followed through any variable that bound names. A receiver that is not a subtype of the
     * declaring class answers the declaring class, as the runtime does.
     */
    private DeclaredType receiverOf(final TreePath reference, final TypeElement declaring) {
      final var fallback = declaring.asType() instanceof DeclaredType dt ? dt : null;
      if (!(trees.getTypeMirror(reference) instanceof DeclaredType target)) return fallback;
      if (target.getTypeArguments().isEmpty()) return fallback;
      var argument = target.getTypeArguments().get(0);
      // A variable's bound may itself be a variable (<U extends Sub, T extends U>).
      while (argument instanceof TypeVariable variable) argument = variable.getUpperBound();
      if (!(argument instanceof DeclaredType receiver)) return fallback;
      if (!types.isSubtype(types.erasure(receiver), types.erasure(declaring.asType()))) return fallback;
      return receiver;
    }

    private Element elementAt(final ExpressionTree tree) {
      final var path = TreePath.getPath(getCurrentPath().getCompilationUnit(), tree);
      return path == null ? null : trees.getElement(path);
    }

    private void report(final Tree at, final String message) {
      trees.printMessage(reportKind, message, at, getCurrentPath().getCompilationUnit());
    }

    private void note(final Tree at, final String message) {
      if (verbose) trees.printMessage(Diagnostic.Kind.NOTE, message, at, getCurrentPath().getCompilationUnit());
    }
  }

  private String pairKey(final TypeMirror src, final TypeMirror tgt) {
    return src.toString() + "→" + tgt.toString();
  }

  /** A pair of classes as construction keys it: by class, whatever type arguments either names. */
  private String pairKey(final TypeElement src, final TypeElement tgt) {
    return binaryName(src) + "→" + binaryName(tgt);
  }

  /** The name construction gives a class in a refusal, {@code Class#getName}. */
  private String binaryName(final TypeElement element) {
    return elements.getBinaryName(element).toString();
  }

  /**
   * The property an accessor named {@code method} names on {@code owner}: a record component by its
   * own name, a bean property with {@code get} or {@code is} stripped.
   */
  private static String propertyName(final String method, final TypeElement owner) {
    return owner.getKind() == ElementKind.RECORD ? method : PropertyNames.property(method);
  }

  /**
   * Enumerate the pairing-relevant properties of a record (components, declaration order) or a bean
   * (public zero-arg {@code getX} / {@code isX} getters — skipping {@code Object}, platform ({@code
   * java.*} / {@code jdk.*}) supertypes, and the {@code class} pseudo-property — mirroring the
   * runtime's getter scan).
   */
  private Map<String, TypeMirror> propertiesOf(final TypeMirror type, final TypeElement element) {
    return properties(type instanceof DeclaredType dt ? dt : null, element);
  }

  /**
   * The same properties as {@link #propertiesOf}, each typed as its accessor declares it rather
   * than as a member of the class, so a type a generic superclass leaves to a variable stays that
   * variable.
   */
  private Map<String, TypeMirror> declaredPropertiesOf(final TypeElement element) {
    return properties(null, element);
  }

  private Map<String, TypeMirror> properties(final DeclaredType declared, final TypeElement element) {
    final var result = new LinkedHashMap<String, TypeMirror>();
    if (element.getKind() == ElementKind.RECORD) {
      for (final var component : element.getRecordComponents()) {
        final var accessor = component.getAccessor();
        final var memberType =
          declared == null
            ? accessor.getReturnType()
            : ((ExecutableType) types.asMemberOf(declared, accessor)).getReturnType();
        result.put(component.getSimpleName().toString(), memberType);
      }
      return result;
    }
    for (final var member : elements.getAllMembers(element)) {
      if (!(member instanceof ExecutableElement method)) continue;
      if (!method.getParameters().isEmpty()) continue;
      if (!method.getModifiers().contains(Modifier.PUBLIC) || method.getModifiers().contains(Modifier.STATIC)) continue;
      final var declaringType = (TypeElement) method.getEnclosingElement();
      // Skip platform supertypes by MODULE name, exactly like the runtime getter scan — a package
      // prefix would miss javax.* packages living inside java.* modules (java.sql, java.naming, …).
      final var declaringModule = elements.getModuleOf(declaringType);
      if (declaringModule != null && !declaringModule.isUnnamed()) {
        final var moduleName = declaringModule.getQualifiedName().toString();
        if (moduleName.startsWith("java.") || moduleName.startsWith("jdk.")) continue;
      }
      final var rawName = method.getSimpleName().toString();
      final var afterGet = PropertyNames.afterGet(rawName);
      final var afterIs = PropertyNames.afterIs(rawName);
      final String prop;
      if (afterGet != null && method.getReturnType().getKind() != TypeKind.VOID) {
        prop = afterGet;
      } else if (afterIs != null && booleanReturn(method)) {
        prop = afterIs;
      } else {
        continue;
      }
      if ("class".equals(prop) || result.containsKey(prop)) continue;
      final var memberType =
        declared == null
          ? method.getReturnType()
          : ((ExecutableType) types.asMemberOf(declared, method)).getReturnType();
      result.put(prop, memberType);
    }
    return result;
  }

  private static boolean booleanReturn(final ExecutableElement method) {
    final var rt = method.getReturnType();
    return rt.getKind() == TypeKind.BOOLEAN || "java.lang.Boolean".equals(rt.toString());
  }

  /**
   * Normalize an accessor-reference name to its property name: {@code getX} / {@code isX} strip +
   * JavaBeans decapitalize (two leading uppers stay, e.g. {@code URL}); record accessors pass
   * through unchanged.
   */
  private static String normalize(final String raw) {
    return PropertyNames.property(raw);
  }
}
