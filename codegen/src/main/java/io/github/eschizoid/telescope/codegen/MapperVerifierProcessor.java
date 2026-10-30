package io.github.eschizoid.telescope.codegen;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
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
import io.github.eschizoid.telescope.internal.pairing.FixedFit;
import io.github.eschizoid.telescope.internal.pairing.KeptNames;
import io.github.eschizoid.telescope.internal.pairing.OwnBridgeRule;
import io.github.eschizoid.telescope.internal.pairing.PairDecision;
import io.github.eschizoid.telescope.internal.pairing.PairWalk;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import io.github.eschizoid.telescope.internal.pairing.SourceWrites;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

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
 * <p>Scope notes, mirroring the runtime exactly: each property no row claims and both sides have
 * under one name is shape-checked on every factory, a {@code mapperForward} call given no rows
 * aside where a bridge of its own pair may convert it. {@code mapperForward} is lenient by
 * contract, so it gets no completeness check, and nor does a site with a {@code constant} or {@code
 * compute} row, which switches the runtime into its permissive mode. Rows that carry a conversion
 * the verifier can't check ({@code to(src, tgt, fwd, bwd)}, {@code toOneWay}, {@code enumTo},
 * {@code via}) are claims only. A row construction applies whose accessor reads no property of its
 * class is reported with construction's refusal. Nested auto-recursed pairs are lenient about
 * unmatched fields (as at runtime) but still shape-checked, leaving out a property a row keyed to
 * the pair claims, and a pair no row is keyed to that a bridge of its own may convert. {@code
 * toOrElse} / {@code toOrElseGet} are claims only: the runtime constructs them without a shape
 * gate, and this verifier never fires where construction succeeds. A row keyed to a type pair the
 * mapper never visits is reported with construction's refusal, but only when every row of the call
 * and every property on the walk can be read; otherwise construction decides alone. Under the same
 * condition, a bean property construction fills through a setter that cannot take the property's
 * own type, or through one of several setters none of which takes it more specifically, is reported
 * with construction's refusal, on the target and, for a mapper that also runs backward, on the
 * source.
 *
 * <p>A {@code cases} row is reported when a class it names is not a permitted subclass of a sealed
 * interface, when two rows pair the same case of the two sealed interfaces the call itself maps,
 * and when the walk from the call's pair reaches no sealed pair at all, so the row is never taken.
 * Where {@code cases} rows pair every case, each pair of cases is checked by property: a pair of
 * the call's own two sealed interfaces as strictly as the call's own pair, and a pair below a field
 * typed by two sealed interfaces as any nested pair. Which case a case without a row pairs with
 * depends on bridges construction finds, so such a pair of sealed interfaces is left to
 * construction.
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
  private static final String BRIDGE_FQN = "io.github.eschizoid.telescope.annotations.Bridge";
  private static final String BRIDGES_FQN = "io.github.eschizoid.telescope.annotations.Bridges";
  private static final Set<String> BRIDGE_FQNS = Set.of(BRIDGE_FQN, BRIDGES_FQN);

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
  private PairWalk<TypeMirror> walk;
  private ContainerAllocation<TypeMirror> allocation;
  private MirrorSetters setters;
  // Answers, as the bridge processor answers it, whether a bridge it generates takes the active
  // path.
  private BridgeProcessor bridges;
  // The compilation units entered so far, by source file, each as last entered; every one is
  // entered before the first call site is scanned.
  private final Map<URI, CompilationUnitTree> units = new LinkedHashMap<>();

  /** The {@code @Bridge} fields of the units entered, in the order entered. */
  private final List<FieldBridge> fieldBridges = new ArrayList<>();

  /**
   * What each {@code @Bridge} field declared in its source's package says of its pair, by pair,
   * read from its rows during the processing rounds. The bridge processor refuses a pair declared
   * twice, and a compilation with an error never reaches the analysis that reads these, so each
   * pair here has the one declaration.
   */
  private final Map<String, OwnBridgeRule.Bridge> fieldSettings = new HashMap<>();

  /** The {@code @Bridge} fields whose rows have been read, as {@code owner#field}. */
  private final Set<String> fieldsRead = new HashSet<>();

  // The binary names of the classes a @Bridge in those units is on or names, read once they are all
  // entered; null until then.
  private Set<String> bridgeNamed;
  // What the model-anchored @Bridge annotations in those units say of each pair they name, by
  // pair; null until read.
  private Map<String, OwnBridgeRule.Bridge> bridgeSettings;
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
    walk = new PairWalk<>(props);
    allocation = new ContainerAllocation<>(props);
    setters = new MirrorSetters(types, elements, props);
    bridges = new BridgeProcessor();
    bridges.init(processingEnv);
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
              recordFieldBridges(event.getCompilationUnit());
              bridgeNamed = null;
              bridgeSettings = null;
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
    // All scanning happens in the post-ANALYZE task listener registered in init(); the rounds only
    // read the rows of each @Bridge field, while its trees are whole. One NOTE when the tree API
    // isn't available (non-javac compiler) — verification is additive, the construction-time
    // validation still applies.
    if (!off && trees != null) readFieldSettings();
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
    collectFieldBridges(named);
    bridgeNamed = named;
    return named;
  }

  /**
   * What each {@code @Bridge} field in this compilation says of its pair: the properties of each
   * side its bridge does not walk ({@link BridgeProcessor.Unwalked}), and whether it takes the
   * active path ({@link BridgeProcessor#takesActivePath}). A {@code @Bridge} on a class has no rows
   * and walks every property of both sides, so it stands in only where nothing the mapper is given
   * lies below the pair, and walking the pair instead finds nothing there to report; it is left
   * out.
   */
  private Map<String, OwnBridgeRule.Bridge> bridgeSettings() {
    if (bridgeSettings != null) return bridgeSettings;
    bridgeSettings = Map.copyOf(fieldSettings);
    return bridgeSettings;
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

  /**
   * The class-literal arguments of the mapper call each {@code @Bridge} field in {@code unit}
   * initialises itself with, recorded as the unit is entered: the compiler drops a class's member
   * trees once it has written the class, so they cannot be read when a later class is verified.
   */
  private void recordFieldBridges(final CompilationUnitTree unit) {
    final var pkg = unit.getPackageName() == null ? "" : unit.getPackageName() + ".";
    for (final var declaration : unit.getTypeDecls()) {
      if (!(declaration instanceof ClassTree type)) continue;
      for (final var member : type.getMembers()) {
        if (!(member instanceof VariableTree field) || !(field.getInitializer() instanceof MethodInvocationTree call)) {
          continue;
        }
        final var annotated = field
          .getModifiers()
          .getAnnotations()
          .stream()
          .map(annotation -> annotation.getAnnotationType().toString())
          .anyMatch(name -> name.equals("Bridge") || name.equals(BRIDGE_FQN));
        if (!annotated || call.getArguments().size() < 2) continue;
        fieldBridges.add(
          new FieldBridge(
            unit,
            pkg + type.getSimpleName(),
            field.getName().toString(),
            List.copyOf(call.getArguments().subList(0, 2))
          )
        );
      }
    }
  }

  /**
   * A {@code @Bridge} field's class, by name, the field's name, and the two class literals its
   * mapper call names.
   */
  private record FieldBridge(
    CompilationUnitTree unit,
    String owner,
    String field,
    List<? extends ExpressionTree> sides
  ) {}

  /**
   * Reads the rows of each recorded {@code @Bridge} field declared in its source's package, as the
   * bridge processor reads them, into what its bridge leaves unwalked: the properties it drops,
   * converts or fills, and the target properties it writes them into. Construction finds such a
   * bridge by the name of its pair, beside the source. A field in another package finds its bridge
   * through its registration, as a {@code @Bridge} on a carrier class does, and is left out. A
   * field whose rows the reader refuses, such as one naming a class a later round writes or a
   * getter Lombok has yet to add, is read again in the next round.
   */
  private void readFieldSettings() {
    final var rows = new FieldRows(processingEnv, trees);
    for (final var bridge : fieldBridges) {
      final var key = bridge.owner() + "#" + bridge.field();
      if (fieldsRead.contains(key)) continue;
      final var owner = elements.getTypeElement(bridge.owner());
      if (owner == null) continue;
      final var field = ElementFilter.fieldsIn(owner.getEnclosedElements())
        .stream()
        .filter(f -> f.getSimpleName().contentEquals(bridge.field()))
        .findFirst()
        .orElse(null);
      if (field == null) continue;
      final var read = rows.read(field);
      if (read instanceof FieldRows.Refused) continue;
      fieldsRead.add(key);
      final var parsed = (FieldRows.Parsed) read;
      if (!elements.getPackageOf(parsed.source()).equals(elements.getPackageOf(owner))) continue;
      final var renamedTo = new HashMap<String, List<String>>();
      for (final var rename : parsed.renames().entrySet()) {
        final var filled = new ArrayList<String>();
        filled.add(rename.getValue());
        filled.addAll(parsed.fanouts().getOrDefault(rename.getKey(), List.of()));
        renamedTo.put(rename.getKey(), filled);
      }
      final var unwalked = BridgeProcessor.Unwalked.of(
        parsed.drops(),
        parsed.transforms().keySet(),
        parsed.viaMappers().keySet(),
        parsed.computes().keySet(),
        renamedTo
      );
      final var pair = pairKey(parsed.source(), parsed.target());
      final var setting = new OwnBridgeRule.Bridge(
        true,
        unwalked.source(),
        unwalked.target(),
        bridges.takesActivePath(parsed.source(), parsed.target())
      );
      fieldSettings.put(pair, setting);
      bridgeSettings = null;
    }
  }

  /** The classes the pair of each recorded {@code @Bridge} field names, resolved by name. */
  private void collectFieldBridges(final Set<String> named) {
    final var rows = new FieldRows(processingEnv, trees);
    for (final var bridge : fieldBridges) {
      final var owner = elements.getTypeElement(bridge.owner());
      if (owner == null) continue;
      for (final var side : bridge.sides()) {
        final var type = rows.classLiteralByName(bridge.unit(), side, owner);
        if (type != null) named.add(binaryName(type));
      }
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

  /**
   * A row and the pair construction keys it to. The methods are the accessors' own names, null for
   * a side given as a telescope path.
   */
  private record KeyedRow(
    Tree at,
    TypeElement source,
    TypeElement target,
    PairWalk.RowKind kind,
    String sourceMethod,
    String targetMethod,
    String sourcePathStart,
    String targetPathStart
  ) {
    /** A row with no side given as a path. */
    KeyedRow(
      final Tree at,
      final TypeElement source,
      final TypeElement target,
      final PairWalk.RowKind kind,
      final String sourceMethod,
      final String targetMethod
    ) {
      this(at, source, target, kind, sourceMethod, targetMethod, null, null);
    }
  }

  /**
   * A pair's properties on each side, as members of a type of each side's class and as declared.
   */
  private record PairSides(
    Map<String, TypeMirror> source,
    Map<String, TypeMirror> target,
    Map<String, TypeMirror> declaredSource,
    Map<String, TypeMirror> declaredTarget
  ) {}

  /**
   * Whether a bridge of its own may convert the pair of {@code source} and {@code target}: either
   * class carries a {@code @Bridge}, a {@code @Bridge} this compilation sees names either one, or a
   * class exists under a name a generated bridge of the source takes, {@code <Source>Bridge} or
   * {@code <Source>To<Target>Bridge} in the source's package, or {@code <Source>Bridge} beside the
   * source in its enclosing class, which is where the runtime looks for a nested source's bridge. A
   * carrier compiled apart from this compilation is seen through the marker it left on the class
   * path or the module path ({@link #carrierCompiledApart}).
   */
  private boolean bridgeVisible(final TypeElement source, final TypeElement target) {
    if (carrierCompiledApart(source, target)) return true;
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

  /**
   * Whether a carrier {@code @Bridge} compiled apart from this compilation converts the pair: its
   * marker resource ({@link CarrierMarkers}) is on the class path, or in a named module on the
   * module path. A module-path location is read one module at a time, so each named module this
   * compilation can see is asked in turn. A carrier in this compilation leaves its marker in this
   * compilation's output, which neither path reads, and is seen through its source instead.
   */
  private boolean carrierCompiledApart(final TypeElement source, final TypeElement target) {
    final var name = CarrierMarkers.relativeName(binaryName(source), binaryName(target));
    if (markerAt(StandardLocation.CLASS_PATH, "", name)) return true;
    for (final var module : elements.getAllModuleElements()) {
      if (module.isUnnamed()) continue;
      if (markerAt(StandardLocation.MODULE_PATH, module.getQualifiedName() + "/", name)) return true;
    }
    return false;
  }

  /**
   * Whether {@code name} can be read at {@code location} under {@code moduleAndPackage}. A file
   * manager may hand back an object for a name it cannot read, so opening it is the test, and any
   * failure, a module the location does not hold included, reads as absent.
   */
  private boolean markerAt(final StandardLocation location, final String moduleAndPackage, final String name) {
    try {
      processingEnv.getFiler().getResource(location, moduleAndPackage, name).openInputStream().close();
      return true;
    } catch (final IOException | RuntimeException missing) {
      return false;
    }
  }

  private final class CallSiteScanner extends TreePathScanner<Void, Void> {

    // The target properties the rows of the call being verified claim, by the nested pair they are
    // keyed to; a nested pair with no entry has no row keyed to it. Read through claimsAt, which
    // adds the rows keyed to two sealed interfaces a pair's classes implement.
    private Map<String, Set<String>> nestedClaims = Map.of();
    // The source properties the rows of the call being verified read, by the nested pair they are
    // keyed to. Read through sourceClaimsAt.
    private Map<String, Set<String>> nestedSourceClaims = Map.of();
    // The source properties the rows of the call being verified read through an accessor, a rename
    // or a drop, by the nested pair they are keyed to. Read through consumedAt.
    private Map<String, Set<String>> nestedConsumed = Map.of();
    // The rows of the call being verified, as the shared walk reads them.
    private List<PairWalk.KeyedRow<TypeMirror>> callRows = List.of();
    // Whether every row of the call being verified was read into callRows, other than a cases row
    // or a hint, which apply to no property.
    private boolean callRowsComplete = true;
    // Whether every row of the call being verified with a side given as a path, a constant or a
    // compute writes one property of the pair it applies at and nothing below it.
    private boolean callPathWritesKnown = true;
    // The rows of callRows that read one source property through a path one field hop long,
    // outside a when, by identity.
    private Set<PairWalk.KeyedRow<TypeMirror>> callOneHopReads = Set.of();
    // The cases rows of the call being verified, each the two classes it pairs.
    private List<TypeElement[]> callCaseRows = List.of();
    // Whether every cases row of the call being verified was read into callCaseRows.
    private boolean callCaseRowsComplete = true;
    // The drop rows of the call being verified given no target class.
    private List<KeyedRow> callTopLevelDrops = List.of();

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
      nestedClaims = Map.of();
      nestedSourceClaims = Map.of();
      nestedConsumed = Map.of();
      callRows = List.of();
      callRowsComplete = true;
      callPathWritesKnown = true;
      callOneHopReads = Set.of();
      callCaseRows = List.of();
      callCaseRowsComplete = true;
      callTopLevelDrops = List.of();
      if (srcEl != null && tgtEl != null) nestedClaims = claimsOf(args, srcEl, tgtEl, strict);
      if (
        srcEl != null &&
        tgtEl != null &&
        rules.decidePair(srcType, tgtType, "", !strict) instanceof PairDecision.DispatchCases
      ) {
        verifySealedCall(args, srcEl, tgtEl);
        verifyRowsVisited(node, args, srcType, tgtType, srcEl, tgtEl, strict);
        final var seen = new HashSet<String>();
        seen.add(pairKey(srcEl, tgtEl));
        verifyOwnCases(node, srcType, tgtType, seen, !strict);
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
      final var writes = new SourceWrites(srcSimple, tgtSimple, strict);
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
        analyzable &= verifyRow(
          row,
          rowExec,
          srcType,
          tgtType,
          srcSimple,
          tgtSimple,
          claimedSrc,
          claimedTgt,
          writes,
          !strict
        );
        permissive |= isPermissiveRow(row, rowExec);
      }

      // A row keyed to two sealed interfaces that construction applies to the call's own pair
      // claims there what it would claim keyed to it.
      for (final var row : inheritedAt(srcEl, tgtEl)) {
        if (row.kind() == PairWalk.RowKind.DROP) {
          claimedSrc.add(propertyName(row.sourceAccessor(), srcEl));
        } else if (row.kind() == PairWalk.RowKind.PASS_THROUGH || row.kind() == PairWalk.RowKind.CONVERTING) {
          claimedSrc.add(propertyName(row.sourceAccessor(), srcEl));
          claimedTgt.add(propertyName(row.targetAccessor(), tgtEl));
        }
      }
      if (!analyzable) {
        note(node, "telescope: some mapping rows are not statically analyzable; completeness deferred to construction");
      }

      // Every row must be readable for the properties no row claims to be known. A mapperForward
      // call given no rows converts through its own pair's bridge where there is one, and walks no
      // property of its own then.
      final var srcProps = propertiesOf(srcType, srcEl);
      final var tgtProps = propertiesOf(tgtType, tgtEl);
      final var rootBridged = !strict && args.size() == 2 && bridgeVisible(srcEl, tgtEl);
      if (analyzable && !rootBridged) {
        final var match = PairingRules.matchFields(
          List.copyOf(srcProps.keySet()),
          List.copyOf(tgtProps.keySet()),
          claimedSrc,
          claimedTgt
        );
        // Completeness only where construction is strict: a factory converting both ways, given no
        // constant or compute row, which turns construction lenient about unmatched properties.
        if (strict && !permissive) {
          final var srcSlot = srcEl.getKind() == ElementKind.RECORD ? "field" : "property";
          final var tgtSlot = tgtEl.getKind() == ElementKind.RECORD ? "field" : "property";
          for (final var name : match.unmatchedTargets()) {
            report(
              node,
              srcProps.containsKey(name)
                ? PairingMessages.sameNameSourceRead(srcSimple, tgtSimple, tgtSlot, srcSlot, name)
                : PairingMessages.noSameNameSource(srcSimple, tgtSimple, tgtSlot, srcSlot, name)
            );
          }
          for (final var name : match.unmatchedSources()) {
            report(node, PairingMessages.noSameNameTarget(srcSimple, tgtSimple, srcSlot, tgtSlot, name));
          }
        } else if (strict) {
          // Beside a constant or compute row construction is lenient about unmatched properties,
          // but still refuses a source property no row reads whose same-named target a row reading
          // another property writes.
          final var kept = new ArrayList<String>();
          for (final var name : srcProps.keySet()) if (!claimedSrc.contains(name)) kept.add(name);
          final var taken = KeptNames.taken(kept, claimedTgt);
          if (!taken.isEmpty()) {
            report(
              node,
              PairingMessages.noSameNameTarget(
                srcSimple,
                tgtSimple,
                srcEl.getKind() == ElementKind.RECORD ? "field" : "property",
                tgtEl.getKind() == ElementKind.RECORD ? "field" : "property",
                taken.getFirst()
              )
            );
          }
          // It refuses as well a target property no row writes whose same-named source a row
          // reads, when no constant or compute row writes it either.
          final var written = fixupTargets(args, tgtEl);
          if (written != null) {
            for (final var name : match.unmatchedTargets()) {
              if (!srcProps.containsKey(name) || written.contains(name)) continue;
              report(
                node,
                PairingMessages.sameNameSourceRead(
                  srcSimple,
                  tgtSimple,
                  tgtEl.getKind() == ElementKind.RECORD ? "field" : "property",
                  srcEl.getKind() == ElementKind.RECORD ? "field" : "property",
                  name
                )
              );
            }
          }
        }
        final var seen = new HashSet<String>();
        seen.add(pairKey(srcEl, tgtEl));
        final var sides = classSides(srcEl, tgtEl);
        for (final var name : match.matched()) {
          verifyProperty(srcEl, tgtEl, sides, name, name, name, node, seen, !strict);
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
     * The two classes a {@code cases} row pairs, reporting nothing, or null when either is not a
     * class literal or is a class no sealed interface permits, which the row's factory refuses.
     */
    private TypeElement[] namedCases(final MethodInvocationTree row) {
      final var rowArgs = row.getArguments();
      final var source = rowArgs.size() == 2 ? classLiteral(rowArgs.get(0)) : null;
      final var target = rowArgs.size() == 2 ? classLiteral(rowArgs.get(1)) : null;
      final var sourceEl = source == null ? null : props.elementOf(source);
      final var targetEl = target == null ? null : props.elementOf(target);
      if (
        sourceEl == null || targetEl == null || sealedRootsOf(sourceEl).isEmpty() || sealedRootsOf(targetEl).isEmpty()
      ) {
        return null;
      }
      return new TypeElement[] { sourceEl, targetEl };
    }

    /**
     * The case pairs of the two sealed interfaces {@code sourceRoot} and {@code targetRoot} as
     * construction pairs them from the call's {@code cases} rows, or null when a case is paired
     * otherwise or not at all, or a {@code cases} row of the call cannot be read.
     */
    private List<PairWalk.Pair<TypeMirror>> casePairs(
      final TypeMirror sourceRoot,
      final TypeMirror targetRoot,
      final boolean forwardOnly
    ) {
      if (!callCaseRowsComplete) return null;
      return new Walking(callCaseRows, forwardOnly).casePairs(sourceRoot, targetRoot);
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
     * refuses them with, and the {@code cases} rows no pair of sealed interfaces it dispatches on
     * takes. Construction keys each row to the classes its accessors are applied to, and to the
     * call's own pair for a side given as a telescope path, for a {@code constant}, {@code compute}
     * or {@code zip} row, and for a target accessor applied to a type the call's target extends. A
     * {@code when} row is keyed as the row it wraps. The pairs the mapper visits are walked as
     * construction walks them ({@link PairWalk}), {@code drop} rows aside.
     *
     * <p>Nothing is reported unless every row can be keyed and every pair of sealed interfaces the
     * walk meets has its cases paired by {@code cases} rows: a row this cannot read could claim a
     * property or reach a pair, a forward-only row on a strict factory is refused by construction
     * first, and a case construction pairs through a bridge of its own may pair with a case this
     * cannot name. A pair a bridge of its own may convert is walked all the same, since a row keyed
     * below it is refused either way when the walk never reaches it.
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
      final var keyed = new ArrayList<KeyedRow>();
      final var caseRows = new ArrayList<TypeElement[]>();
      final var caseRowsAt = new ArrayList<Tree>();
      var caseRowsNamed = true;
      var writeHinted = false;
      var nullHinted = false;
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
        if (NULL_HINT_FQN.equals(ownerFqn)) {
          nullHinted = true;
          continue;
        }
        if (CASES_FQN.equals(ownerFqn)) {
          final var cases = namedCases(row);
          if (cases == null) {
            caseRowsNamed = false;
            continue;
          }
          caseRows.add(cases);
          caseRowsAt.add(row);
          continue;
        }
        if (!MAPPING_FQN.equals(ownerFqn)) return;
        final var entry = keyedRow(row, rowExec, srcEl, tgtEl, strict);
        if (entry == null) return;
        keyed.add(entry);
      }
      final var rows = new ArrayList<PairWalk.KeyedRow<TypeMirror>>(keyed.size());
      final var rowsAt = new IdentityHashMap<PairWalk.KeyedRow<TypeMirror>, Tree>();
      final var keyedPairs = new HashSet<String>();
      for (final var row : keyed) {
        final var shared = shared(row);
        rows.add(shared);
        rowsAt.put(shared, row.at());
        keyedPairs.add(pairKey(row.source(), row.target()));
      }
      final var forwardOnly = !strict;
      // A hint may reach every pair or name a class a pair reaches, so a call given one walks every
      // pair here, which can only leave a refusal to construction.
      final var bridged = writeHinted || nullHinted ? null : ownBridgeRule(rows, caseRows);
      final var walked = walk.walk(
        srcType,
        tgtType,
        rows,
        forwardOnly,
        new Walking(caseRows, forwardOnly) {
          @Override
          public boolean ownBridge(
            final TypeMirror source,
            final TypeMirror target,
            final List<PairWalk.Pair<TypeMirror>> around
          ) {
            return bridged != null && standsIn(bridged, source, target, around);
          }
        }
      );
      verifyRowsReadProperties(keyed, walked);
      for (final var refused : walked.offCase()) report(rowsAt.get(refused.row()), refused.message());
      verifyInheritedRows(walked, rows, rowsAt, forwardOnly);
      verifyRowsClaimOnce(walked, rows, rowsAt, srcEl, tgtEl, strict);
      if (walked.open()) return;
      // A write hint picks the writer a bean is built with and keeps its pairs from their own
      // bridges, so the setters such a call writes through are left to construction, as are those
      // of the cases of a call between two sealed interfaces. A mapperForward call given no rows
      // converts through its own pair's bridge where there is one.
      final var sealedRoot = rules.decidePair(srcType, tgtType, "", forwardOnly) instanceof PairDecision.DispatchCases;
      if (!writeHinted && !sealedRoot) {
        verifySetters(node, srcType, tgtType, rows, keyedPairs, caseRows, forwardOnly, forwardOnly && args.size() == 2);
      }
      if (caseRowsNamed) {
        final var untaken = new ArrayList<String>();
        Tree firstUntaken = null;
        for (var i = 0; i < caseRows.size(); i++) {
          final var row = caseRows.get(i);
          final var taken = walked
            .dispatched()
            .stream()
            .anyMatch(
              pair -> permits(props.elementOf(pair.source()), row[0]) && permits(props.elementOf(pair.target()), row[1])
            );
          if (taken) continue;
          untaken.add(PairingMessages.caseRow(row[0].getSimpleName().toString(), row[1].getSimpleName().toString()));
          if (firstUntaken == null) firstUntaken = caseRowsAt.get(i);
        }
        if (!untaken.isEmpty()) {
          report(
            firstUntaken,
            PairingMessages.caseRowsNeverDispatched(
              srcEl.getSimpleName().toString(),
              tgtEl.getSimpleName().toString(),
              untaken
            )
          );
        }
      }
      if (walked.refusal() != null) report(rowsAt.get(walked.neverVisited().getFirst()), walked.refusal());
    }

    /**
     * Reports each row construction applies whose accessor reads no property of the class the row
     * is keyed to on its side, with the message construction refuses it with: a row with accessors
     * on both sides, or a {@code drop}, on a pair of records or beans the walk visited. A row keyed
     * to two sealed interfaces is applied to each pair of their cases instead, where its accessors
     * are read on the cases' classes ({@link PairWalk#offCase}), so it is left out here. The source
     * accessor is read first, as construction reads it.
     */
    private void verifyRowsReadProperties(final List<KeyedRow> keyed, final PairWalk.Walk<TypeMirror> walked) {
      final var visited = new HashSet<String>();
      for (final var pair : walked.visited()) {
        final var sourceEl = props.elementOf(pair.source());
        final var targetEl = props.elementOf(pair.target());
        if (sourceEl != null && targetEl != null) visited.add(pairKey(sourceEl, targetEl));
      }
      for (final var pair : walked.dispatched()) {
        visited.remove(pairKey(props.elementOf(pair.source()), props.elementOf(pair.target())));
      }
      for (final var row : keyed) {
        if (row.kind() == PairWalk.RowKind.PATH || !visited.contains(pairKey(row.source(), row.target()))) continue;
        var refusal = unreadRefusal(row.sourceMethod(), row.source());
        if (refusal == null && row.kind() != PairWalk.RowKind.DROP) {
          refusal = unreadRefusal(row.targetMethod(), row.target());
        }
        if (refusal != null) report(row.at(), refusal);
      }
    }

    /**
     * Construction's refusal of an accessor named {@code method} that reads no property of {@code
     * owner}, or null when it reads one.
     */
    private String unreadRefusal(final String method, final TypeElement owner) {
      if (method == null) return null;
      final var properties = declaredPropertiesOf(owner);
      final var name = propertyName(method, owner);
      if (properties.containsKey(name)) return null;
      final var binary = binaryName(owner);
      return owner.getKind() == ElementKind.RECORD
        ? PairingMessages.noComponent(name, binary, List.copyOf(properties.keySet()))
        : PairingMessages.noGetter(name, binary);
    }

    /**
     * The target properties each row of a call claims, by the pair it is keyed to, for each pair
     * other than the call's own: those a row with accessors on both sides names, which construction
     * converts as the row says rather than by name. A {@code drop} claims none.
     */
    private Map<String, Set<String>> claimsOf(
      final List<? extends ExpressionTree> args,
      final TypeElement srcEl,
      final TypeElement tgtEl,
      final boolean strict
    ) {
      final var claims = new HashMap<String, Set<String>>();
      final var sourceClaims = new HashMap<String, Set<String>>();
      nestedSourceClaims = sourceClaims;
      final var consumed = new HashMap<String, Set<String>>();
      nestedConsumed = consumed;
      final var all = new ArrayList<PairWalk.KeyedRow<TypeMirror>>();
      callRows = all;
      final var caseRows = new ArrayList<TypeElement[]>();
      callCaseRows = caseRows;
      final var topLevelDrops = new ArrayList<KeyedRow>();
      callTopLevelDrops = topLevelDrops;
      final var oneHopReads = Collections.<PairWalk.KeyedRow<TypeMirror>>newSetFromMap(new IdentityHashMap<>());
      callOneHopReads = oneHopReads;
      for (var i = 2; i < args.size(); i++) {
        if (!(args.get(i) instanceof MethodInvocationTree row)) {
          callRowsComplete = false;
          continue;
        }
        if (
          !(elementAt(row.getMethodSelect()) instanceof ExecutableElement rowExec) ||
          !(rowExec.getEnclosingElement() instanceof TypeElement rowOwner)
        ) {
          callRowsComplete = false;
          continue;
        }
        final var owner = rowOwner.getQualifiedName().toString();
        if (CASES_FQN.equals(owner)) {
          final var cases = namedCases(row);
          if (cases == null) callCaseRowsComplete = false;
          else caseRows.add(cases);
          continue;
        }
        if (!MAPPING_FQN.equals(owner)) {
          if (!WRITE_HINT_FQN.equals(owner) && !NULL_HINT_FQN.equals(owner)) callRowsComplete = false;
          continue;
        }
        final var keyed = keyedRow(row, rowExec, srcEl, tgtEl, strict);
        if (keyed == null) {
          callRowsComplete = false;
          continue;
        }
        if (keyed.kind() == PairWalk.RowKind.DROP && row.getArguments().size() == 1) topLevelDrops.add(keyed);
        final var key = pairKey(keyed.source(), keyed.target());
        final var sharedRow = shared(keyed);
        all.add(sharedRow);
        final var oneHop = keyed.kind() == PairWalk.RowKind.PATH && readsOneSourceProperty(row, rowExec);
        if (oneHop) oneHopReads.add(sharedRow);
        if (keyed.kind() == PairWalk.RowKind.PATH && !writesOneTargetProperty(row, tgtEl)) {
          callPathWritesKnown = false;
        }
        if (key.equals(pairKey(srcEl, tgtEl))) continue;
        final var names = claims.computeIfAbsent(key, __ -> new HashSet<>());
        if (keyed.kind() == PairWalk.RowKind.PASS_THROUGH || keyed.kind() == PairWalk.RowKind.CONVERTING) {
          names.add(propertyName(keyed.targetMethod(), keyed.target()));
        }
        final var read = readsAt(keyed.kind(), keyed.sourceMethod(), keyed.sourcePathStart(), keyed.source());
        if (read != null) sourceClaims.computeIfAbsent(key, __ -> new HashSet<>()).add(read);
        if (read != null && (keyed.kind() != PairWalk.RowKind.PATH || oneHop)) {
          consumed.computeIfAbsent(key, __ -> new HashSet<>()).add(read);
        }
      }
      return claims;
    }

    /**
     * The target properties the rows construction applies to the pair of {@code source} and {@code
     * target} claim, or null when no row applies to it: those keyed to the pair, and those of each
     * row keyed to two sealed interfaces its classes implement ({@link PairWalk#inheritsRows}),
     * whose target accessor names the property on {@code target}. A {@code drop} claims none,
     * though it applies to the pair.
     */
    private Set<String> claimsAt(final TypeElement source, final TypeElement target) {
      final var own = nestedClaims.get(pairKey(source, target));
      Set<String> all = own;
      for (final var row : inheritedAt(source, target)) {
        if (all == own) all = own == null ? new HashSet<>() : new HashSet<>(own);
        if (row.kind() == PairWalk.RowKind.PASS_THROUGH || row.kind() == PairWalk.RowKind.CONVERTING) {
          all.add(propertyName(row.targetAccessor(), target));
        }
      }
      return all;
    }

    /**
     * The source properties the rows construction applies to the pair of {@code source} and {@code
     * target} read: those keyed to the pair, and those keyed to two sealed interfaces its classes
     * implement, whose source accessor or path names the property on {@code source}.
     */
    private Set<String> sourceClaimsAt(final TypeElement source, final TypeElement target) {
      final var all = new HashSet<>(nestedSourceClaims.getOrDefault(pairKey(source, target), Set.of()));
      for (final var row : inheritedAt(source, target)) {
        final var read = readsAt(row.kind(), row.sourceAccessor(), row.sourcePathStart(), source);
        if (read != null) all.add(read);
      }
      return all;
    }

    /**
     * The source properties the rows construction applies to the pair of {@code source} and {@code
     * target} read through an accessor, a rename, a drop or a {@code to(path, accessor)} row whose
     * path is one {@code field} hop long, which keep the target property of each one's name from
     * pairing with it: those keyed to the pair, and those keyed to two sealed interfaces its
     * classes implement.
     */
    private Set<String> consumedAt(final TypeElement source, final TypeElement target) {
      final var all = new HashSet<>(nestedConsumed.getOrDefault(pairKey(source, target), Set.of()));
      for (final var row : inheritedAt(source, target)) {
        if (row.kind() == PairWalk.RowKind.PATH && !callOneHopReads.contains(row)) continue;
        final var read = readsAt(row.kind(), row.sourceAccessor(), row.sourcePathStart(), source);
        if (read != null) all.add(read);
      }
      return all;
    }

    /**
     * Whether every row of the call was read and each row with a side given as a path, a constant
     * or a compute writes one property of the pair it applies at and nothing below it, which {@link
     * #pathWritesAt} names. Which property any other such row writes below its pair is not read
     * here.
     */
    private boolean nestedWritesKnown() {
      return callRowsComplete && callPathWritesKnown;
    }

    /**
     * The target properties the rows with a side given as a path, a constant or a compute that
     * construction applies to the pair of {@code source} and {@code target} write, each named by
     * the row's target accessor or the first hop of its target path, or null when one of them names
     * its target in a way this does not read.
     */
    private Set<String> pathWritesAt(final TypeElement source, final TypeElement target) {
      final var written = new HashSet<String>();
      final var applied = new ArrayList<>(
        rowsKeyedTo(callRows, types.erasure(source.asType()), types.erasure(target.asType()))
      );
      applied.addAll(inheritedAt(source, target));
      for (final var row : applied) {
        if (row.kind() != PairWalk.RowKind.PATH) continue;
        final var start = row.targetAccessor() != null ? row.targetAccessor() : row.targetPathStart();
        if (start == null) return null;
        written.add(propertyName(start, target));
      }
      return written;
    }

    /**
     * Whether {@code row}, or the row a {@code when} wraps, writes one property of the pair it is
     * keyed to and nothing below it: a {@code constant} or {@code compute} whose target is an
     * accessor of {@code target} or a class it extends, or a path one {@code field} hop long, or a
     * {@code to} row whose target is an accessor.
     */
    private boolean writesOneTargetProperty(final MethodInvocationTree row, final TypeElement target) {
      var call = row;
      if (
        elementAt(call.getMethodSelect()) instanceof ExecutableElement outer &&
        outer.getSimpleName().contentEquals("when") &&
        call.getArguments().size() == 2 &&
        call.getArguments().get(1) instanceof MethodInvocationTree inner
      ) {
        call = inner;
      }
      if (!(elementAt(call.getMethodSelect()) instanceof ExecutableElement exec)) return false;
      final var name = factoryName(call);
      if (
        "to".equals(name) &&
        call.getArguments().size() == 2 &&
        isTelescopeParameter(exec, 0) &&
        !isTelescopeParameter(exec, 1)
      ) {
        return true;
      }
      if ((!"constant".equals(name) && !"compute".equals(name)) || call.getArguments().isEmpty()) return false;
      final var arg = call.getArguments().getFirst();
      final var accessor = accessorProp(arg);
      if (accessor != null) {
        return types.isSubtype(types.erasure(target.asType()), types.erasure(accessor.owner().asType()));
      }
      return oneFieldHop(arg);
    }

    /**
     * Whether {@code row}, given outside a {@code when}, is a {@code to(path, accessor)} row whose
     * source path is one {@code field} hop long, which reads that one property as its accessor
     * does.
     */
    private boolean readsOneSourceProperty(final MethodInvocationTree row, final ExecutableElement rowExec) {
      return (
        rowExec.getSimpleName().contentEquals("to") &&
        row.getArguments().size() == 2 &&
        isTelescopeParameter(rowExec, 0) &&
        !isTelescopeParameter(rowExec, 1) &&
        oneFieldHop(row.getArguments().getFirst())
      );
    }

    /**
     * Whether {@code arg} is a telescope path written inline as {@code Telescope.of} or {@code
     * ofBean} followed by one {@code field} hop and nothing else.
     */
    private boolean oneFieldHop(final ExpressionTree arg) {
      final var calls = telescopeCalls(arg);
      if (calls == null || calls.size() != 2) return false;
      final var root = factoryName(calls.getFirst());
      return (
        ("of".equals(root) || "ofBean".equals(root)) &&
        ((MemberSelectTree) calls.get(1).getMethodSelect()).getIdentifier().contentEquals("field") &&
        calls.get(1).getArguments().size() == 1
      );
    }

    /**
     * The properties of {@code target} the call's {@code constant} and {@code compute} rows write,
     * each named by its accessor or its path's first hop, or null when one of them names its target
     * in a way this does not read.
     */
    private Set<String> fixupTargets(final List<? extends ExpressionTree> args, final TypeElement target) {
      final var written = new HashSet<String>();
      for (var i = 2; i < args.size(); i++) {
        if (!(args.get(i) instanceof MethodInvocationTree row)) continue;
        var call = row;
        if (
          elementAt(call.getMethodSelect()) instanceof ExecutableElement outer &&
          outer.getSimpleName().contentEquals("when") &&
          call.getArguments().size() == 2 &&
          call.getArguments().get(1) instanceof MethodInvocationTree inner
        ) {
          call = inner;
        }
        if (!(elementAt(call.getMethodSelect()) instanceof ExecutableElement)) continue;
        final var name = factoryName(call);
        if (!"constant".equals(name) && !"compute".equals(name)) continue;
        final var arg = call.getArguments().isEmpty() ? null : call.getArguments().getFirst();
        final var accessor = accessorProp(arg);
        final var start = accessor != null ? accessor.method() : pathStart(arg);
        if (start == null) return null;
        written.add(propertyName(start, target));
      }
      return written;
    }

    /**
     * The source property a row of {@code kind} reads on {@code source}, through its accessor
     * {@code method} or the first step {@code pathStart} of its path, or null when it reads none
     * there.
     */
    private String readsAt(
      final PairWalk.RowKind kind,
      final String method,
      final String pathStart,
      final TypeElement source
    ) {
      if (kind == PairWalk.RowKind.PATH && pathStart != null) return propertyName(pathStart, source);
      return method == null ? null : propertyName(method, source);
    }

    /**
     * The rows of the call keyed to two sealed interfaces that construction applies to the pair of
     * {@code source} and {@code target} ({@link PairWalk#inherited}), given the rows keyed to that
     * pair. A row the pair refuses is reported by the walk.
     */
    private List<PairWalk.KeyedRow<TypeMirror>> inheritedAt(final TypeElement source, final TypeElement target) {
      if (callRows.isEmpty()) return List.of();
      final var sourceType = types.erasure(source.asType());
      final var targetType = types.erasure(target.asType());
      return walk.inherited(callRows, rowsKeyedTo(callRows, sourceType, targetType), sourceType, targetType).applied();
    }

    /** The rows among {@code rows} keyed to the pair of {@code source} and {@code target}. */
    private List<PairWalk.KeyedRow<TypeMirror>> rowsKeyedTo(
      final List<PairWalk.KeyedRow<TypeMirror>> rows,
      final TypeMirror source,
      final TypeMirror target
    ) {
      return rows
        .stream()
        .filter(
          each ->
            types.isSameType(types.erasure(each.source()), source) &&
            types.isSameType(types.erasure(each.target()), target)
        )
        .toList();
    }

    /** {@code row} as the shared walk reads it. */
    private PairWalk.KeyedRow<TypeMirror> shared(final KeyedRow row) {
      return new PairWalk.KeyedRow<>(
        row.source().asType(),
        row.target().asType(),
        row.kind(),
        row.sourceMethod(),
        row.targetMethod(),
        row.sourcePathStart(),
        row.targetPathStart()
      );
    }

    /**
     * Reports two rows construction applies to one pair the walk visited that claim one property of
     * it, with the message construction refuses the later with: two converting the same target
     * property, a {@code drop} of a source property another row writes or drops, or, in a mapper
     * that converts both ways ({@code strict}), a source property written into a second target. The
     * rows are those keyed to the pair followed by those keyed to two sealed interfaces it applies
     * ({@link PairWalk#inherited}). On the call's own pair, two of its own rows are reported where
     * they are read.
     */
    private void verifyRowsClaimOnce(
      final PairWalk.Walk<TypeMirror> walked,
      final List<PairWalk.KeyedRow<TypeMirror>> rows,
      final Map<PairWalk.KeyedRow<TypeMirror>, Tree> rowsAt,
      final TypeElement srcEl,
      final TypeElement tgtEl,
      final boolean strict
    ) {
      final var dispatched = new HashSet<String>();
      for (final var pair : walked.dispatched()) {
        dispatched.add(pairKey(props.elementOf(pair.source()), props.elementOf(pair.target())));
      }
      final var root = pairKey(srcEl, tgtEl);
      for (final var pair : walked.visited()) {
        final var sourceEl = props.elementOf(pair.source());
        final var targetEl = props.elementOf(pair.target());
        if (sourceEl == null || targetEl == null) continue;
        final var key = pairKey(sourceEl, targetEl);
        if (dispatched.contains(key)) continue;
        final var own = rowsKeyedTo(rows, pair.source(), pair.target());
        final var applied = new ArrayList<>(own);
        applied.addAll(walk.inherited(rows, own, pair.source(), pair.target()).applied());
        final var claimedSrc = new HashSet<String>();
        final var claimedTgt = new HashSet<String>();
        final var sourceName = sourceEl.getSimpleName().toString();
        final var targetName = targetEl.getSimpleName().toString();
        final var writes = new SourceWrites(sourceName, targetName, strict);
        for (final var row : applied) {
          final var reported = !(key.equals(root) && own.contains(row));
          if (row.kind() == PairWalk.RowKind.DROP) {
            final var dropped = propertyName(row.sourceAccessor(), sourceEl);
            final var claimed = writes.drop(dropped);
            if (claimed != null) {
              if (reported) report(rowsAt.get(row), claimed);
            } else if (!claimedSrc.add(dropped) && reported) {
              report(rowsAt.get(row), PairingMessages.duplicateSourceRow(sourceName, targetName, dropped));
            }
          } else if (row.kind() == PairWalk.RowKind.PASS_THROUGH || row.kind() == PairWalk.RowKind.CONVERTING) {
            final var written = propertyName(row.targetAccessor(), targetEl);
            if (!claimedTgt.add(written)) {
              if (reported) report(
                rowsAt.get(row),
                PairingMessages.duplicateTargetRow(sourceName, targetName, written)
              );
              continue;
            }
            final var read = propertyName(row.sourceAccessor(), sourceEl);
            final var claimed = writes.write(read, written);
            if (claimed != null) {
              if (reported) report(rowsAt.get(row), claimed);
              continue;
            }
            claimedSrc.add(read);
          }
        }
      }
    }

    /**
     * Replays, on each pair of classes the walk visited, the pairing of each same-typed {@code to}
     * row keyed to two sealed interfaces that construction applies to that pair as it does a row
     * keyed to it ({@link PairWalk#inherited}), with the property names the row's accessors give on
     * the pair's classes. A row the pair refuses is reported by the walk.
     */
    private void verifyInheritedRows(
      final PairWalk.Walk<TypeMirror> walked,
      final List<PairWalk.KeyedRow<TypeMirror>> rows,
      final Map<PairWalk.KeyedRow<TypeMirror>, Tree> rowsAt,
      final boolean forwardOnly
    ) {
      for (final var pair : walked.visited()) {
        final var sourceEl = props.elementOf(pair.source());
        final var targetEl = props.elementOf(pair.target());
        if (sourceEl == null || targetEl == null) continue;
        final var own = rowsKeyedTo(rows, pair.source(), pair.target());
        for (final var row : walk.inherited(rows, own, pair.source(), pair.target()).applied()) {
          if (row.kind() != PairWalk.RowKind.PASS_THROUGH) continue;
          final var at = rowsAt.get(row);
          if (!(at instanceof MethodInvocationTree call) || call.getArguments().size() != 2) continue;
          final var srcName = propertyName(row.sourceAccessor(), sourceEl);
          final var tgtName = propertyName(row.targetAccessor(), targetEl);
          verifyProperty(
            sourceEl,
            targetEl,
            classSides(sourceEl, targetEl),
            srcName,
            tgtName,
            srcName + " → " + tgtName,
            at,
            new HashSet<>(),
            forwardOnly
          );
        }
      }
    }

    /** The shared decision on a nested pair's own bridge, given the rows of the call. */
    private OwnBridgeRule<TypeMirror> ownBridgeRule(
      final List<PairWalk.KeyedRow<TypeMirror>> rows,
      final List<TypeElement[]> caseRows
    ) {
      final var cases = new ArrayList<PairWalk.Pair<TypeMirror>>();
      for (final var row : caseRows) {
        cases.add(new PairWalk.Pair<>(types.erasure(row[0].asType()), types.erasure(row[1].asType())));
      }
      return new OwnBridgeRule<>(props, rules, walk, null, rows, cases, List.of());
    }

    /**
     * Whether construction certainly converts the pair of {@code source} and {@code target} through
     * a bridge of its own rather than walking it: a {@code @Bridge} field in this compilation names
     * the pair, whose settings say which properties the bridge walks and whether it takes the
     * active path ({@link #bridgeSettings}), and the shared decision lets it stand in.
     */
    private boolean standsIn(
      final OwnBridgeRule<TypeMirror> rule,
      final TypeMirror source,
      final TypeMirror target,
      final List<PairWalk.Pair<TypeMirror>> around
    ) {
      final var sourceEl = props.elementOf(source);
      final var targetEl = props.elementOf(target);
      if (sourceEl == null || targetEl == null) return false;
      final var bridge = bridgeSettings().get(pairKey(sourceEl, targetEl));
      if (bridge == null || rule.settingReason(source, target, around) != null) return false;
      return rule.reachReason(source, target, around, bridge) == null;
    }

    /**
     * The walk's questions, as far as this compilation can answer them. Every pair is walked, since
     * whether a bridge of its own stands in for one cannot be told here. The cases of two sealed
     * interfaces pair as the {@code cases} rows naming them pair them, which is how construction
     * pairs them first; a case no row names pairs through a bridge construction finds, which this
     * cannot see, so the pairing is unknown, and so is one construction refuses: two rows pairing
     * one case, type arguments a case pairing crosses, or, for a mapper that also runs backward, a
     * case of the target no case or several cases pair with.
     */
    private class Walking implements PairWalk.Decisions<TypeMirror> {

      private final List<TypeElement[]> caseRows;
      private final boolean forwardOnly;

      Walking(final List<TypeElement[]> caseRows, final boolean forwardOnly) {
        this.caseRows = caseRows;
        this.forwardOnly = forwardOnly;
      }

      @Override
      public boolean ownBridge(
        final TypeMirror source,
        final TypeMirror target,
        final List<PairWalk.Pair<TypeMirror>> around
      ) {
        return false;
      }

      @Override
      public List<PairWalk.Pair<TypeMirror>> casePairs(final TypeMirror sourceRoot, final TypeMirror targetRoot) {
        final var sourceRootEl = props.elementOf(sourceRoot);
        final var targetRootEl = props.elementOf(targetRoot);
        if (sourceRootEl == null || targetRootEl == null) return null;
        final var named = new HashMap<String, TypeElement>();
        for (final var row : caseRows) {
          if (!permits(sourceRootEl, row[0]) || !permits(targetRootEl, row[1])) continue;
          if (named.putIfAbsent(binaryName(row[0]), row[1]) != null) return null;
        }
        final var pairs = new ArrayList<PairWalk.Pair<TypeMirror>>();
        final var fromSources = new HashMap<String, Integer>();
        for (final var sourceCase : props.permittedSubclasses(sourceRoot)) {
          final var sourceCaseEl = props.elementOf(sourceCase);
          final var targetCaseEl = sourceCaseEl == null ? null : named.get(binaryName(sourceCaseEl));
          if (targetCaseEl == null) return null;
          final var targetCase = types.erasure(targetCaseEl.asType());
          if (rules.caseArgumentsRefusal(sourceRoot, targetRoot, sourceCase, targetCase) != null) return null;
          pairs.add(new PairWalk.Pair<>(sourceCase, targetCase));
          fromSources.merge(binaryName(targetCaseEl), 1, Integer::sum);
        }
        if (!forwardOnly) {
          for (final var targetCase : props.permittedSubclasses(targetRoot)) {
            final var targetCaseEl = props.elementOf(targetCase);
            if (targetCaseEl == null || fromSources.getOrDefault(binaryName(targetCaseEl), 0) != 1) return null;
          }
        }
        return pairs;
      }
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
     * of its own may convert, which the same test then decides.
     */
    private void verifySetters(
      final MethodInvocationTree node,
      final TypeMirror srcType,
      final TypeMirror tgtType,
      final List<PairWalk.KeyedRow<TypeMirror>> rows,
      final Set<String> keyedPairs,
      final List<TypeElement[]> caseRows,
      final boolean forwardOnly,
      final boolean rootBridgeable
    ) {
      final var srcEl = props.elementOf(srcType);
      final var tgtEl = props.elementOf(tgtType);
      if (srcEl == null || tgtEl == null) return;
      if (rootBridgeable && !keyedPairs.contains(pairKey(srcEl, tgtEl)) && bridgeVisible(srcEl, tgtEl)) return;
      final var refusals = new LinkedHashSet<String>();
      final var sidesByPair = new HashMap<String, PairSides>();
      walk.walk(
        srcType,
        tgtType,
        rows,
        forwardOnly,
        new Walking(caseRows, forwardOnly) {
          @Override
          public boolean ownBridge(
            final TypeMirror source,
            final TypeMirror target,
            final List<PairWalk.Pair<TypeMirror>> around
          ) {
            final var sourceEl = props.elementOf(source);
            final var targetEl = props.elementOf(target);
            return !keyedPairs.contains(pairKey(sourceEl, targetEl)) && bridgeVisible(sourceEl, targetEl);
          }

          @Override
          public void fills(
            final TypeMirror source,
            final TypeMirror target,
            final String sourceName,
            final String targetName
          ) {
            final var sourceEl = props.elementOf(source);
            final var targetEl = props.elementOf(target);
            final var sides = sidesByPair.computeIfAbsent(pairKey(sourceEl, targetEl), __ ->
              classSides(sourceEl, targetEl)
            );
            addRefusal(refusals, targetEl, sides.target(), targetName);
            if (!forwardOnly) addRefusal(refusals, sourceEl, sides.source(), sourceName);
          }
        }
      );
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
          if (rowArgs.size() == 4 || rowArgs.size() == 3) return accessorRow(row, PairWalk.RowKind.CONVERTING);
          if (rowArgs.size() != 2) return null;
          final var srcPath = isTelescopeParameter(rowExec, 0);
          final var tgtPath = isTelescopeParameter(rowExec, 1);
          if (!srcPath && !tgtPath) return accessorRow(row, PairWalk.RowKind.PASS_THROUGH);
          if (srcPath && tgtPath) return new KeyedRow(
            row,
            srcEl,
            tgtEl,
            PairWalk.RowKind.PATH,
            null,
            null,
            pathStart(rowArgs.get(0)),
            pathStart(rowArgs.get(1))
          );
          if (!srcPath) {
            final var src = accessorProp(rowArgs.get(0));
            return src == null
              ? null
              : new KeyedRow(
                  row,
                  src.owner(),
                  tgtEl,
                  PairWalk.RowKind.PATH,
                  src.method(),
                  null,
                  null,
                  pathStart(rowArgs.get(1))
                );
          }
          final var tgt = accessorProp(rowArgs.get(1));
          if (tgt == null) return null;
          final var onTarget = types.isSubtype(types.erasure(tgtEl.asType()), types.erasure(tgt.owner().asType()));
          return new KeyedRow(
            row,
            srcEl,
            onTarget ? tgtEl : tgt.owner(),
            PairWalk.RowKind.PATH,
            null,
            tgt.method(),
            pathStart(rowArgs.get(0)),
            null
          );
        }
        case "toOneWay" -> {
          return strict ? null : accessorRow(row, PairWalk.RowKind.CONVERTING);
        }
        case "enumTo", "toOrElse", "toOrElseGet", "via" -> {
          return accessorRow(row, PairWalk.RowKind.CONVERTING);
        }
        case "drop" -> {
          final var src = accessorProp(rowArgs.isEmpty() ? null : rowArgs.get(0));
          if (src == null) return null;
          if (rowArgs.size() == 1) return new KeyedRow(
            row,
            src.owner(),
            tgtEl,
            PairWalk.RowKind.DROP,
            src.method(),
            null
          );
          final var explicit = rowArgs.size() == 2 ? classLiteral(rowArgs.get(1)) : null;
          final var explicitEl = explicit == null ? null : props.elementOf(explicit);
          return explicitEl == null
            ? null
            : new KeyedRow(row, src.owner(), explicitEl, PairWalk.RowKind.DROP, src.method(), null);
        }
        case "when" -> {
          return whenRow(row, srcEl, tgtEl, strict);
        }
        case "constant", "compute" -> {
          final var arg = rowArgs.isEmpty() ? null : rowArgs.get(0);
          final var tgt = accessorProp(arg);
          final var start = tgt != null ? tgt.method() : pathStart(arg);
          return new KeyedRow(row, srcEl, tgtEl, PairWalk.RowKind.PATH, null, null, null, start);
        }
        case "zip" -> {
          return new KeyedRow(
            row,
            srcEl,
            tgtEl,
            PairWalk.RowKind.PATH,
            null,
            null,
            pathStart(rowArgs.get(0)),
            pathStart(rowArgs.get(1))
          );
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
      if (keyed == null || keyed.kind() != PairWalk.RowKind.PATH) return null;
      return new KeyedRow(
        row,
        keyed.source(),
        keyed.target(),
        PairWalk.RowKind.PATH,
        keyed.sourceMethod(),
        keyed.targetMethod(),
        keyed.sourcePathStart(),
        keyed.targetPathStart()
      );
    }

    /**
     * The method name the first step of the telescope path {@code arg} reads its root through, as
     * construction reads a path's first hop, or null where the path is not written inline as a
     * chain from {@code Telescope.of} or {@code ofBean} whose first step reads through an accessor.
     */
    private String pathStart(final ExpressionTree arg) {
      final var calls = arg == null ? null : telescopeCalls(arg);
      if (calls == null) return null;
      final var rootName = factoryName(calls.getFirst());
      if (!"of".equals(rootName) && !"ofBean".equals(rootName)) return null;
      for (final var call : calls.subList(1, calls.size())) {
        final var name = ((MemberSelectTree) call.getMethodSelect()).getIdentifier().toString();
        if ("filter".equals(name) || "observe".equals(name) || "as".equals(name)) continue;
        if (!ROOT_HOPS.contains(name) || call.getArguments().size() != 1) return null;
        final var hop = accessorProp(call.getArguments().getFirst());
        return hop == null ? null : hop.method();
      }
      return null;
    }

    /** A row given two accessors, keyed to the classes they are applied to, or null. */
    private KeyedRow accessorRow(final MethodInvocationTree row, final PairWalk.RowKind kind) {
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
      final SourceWrites writes,
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
            final var claimed = writes.write(src.name(), tgt.name());
            if (claimed != null) {
              report(row, claimed);
              return true;
            }
          }
          // Only the 2-arg same-typed to(src, tgt) carries no conversion — the one row shape the
          // runtime itself routes through the pairing decision at construction. Every other form
          // carries user functions (or a user default), which construction accepts as-is.
          if ("to".equals(rowName) && rowArgs.size() == 2) {
            final var seen = new HashSet<String>();
            final var componentName = src.name() + " → " + tgt.name();
            final var sides = classSides(src.owner(), tgt.owner());
            final var srcName = propertyName(src.method(), src.owner());
            final var tgtName = propertyName(tgt.method(), tgt.owner());
            // An accessor that reads no property is refused by construction for that alone.
            if (sides.source().containsKey(srcName) && sides.target().containsKey(tgtName)) {
              verifyProperty(src.owner(), tgt.owner(), sides, srcName, tgtName, componentName, row, seen, forwardOnly);
            }
          }
          return true;
        }
        case "drop" -> {
          final var src = accessorProp(rowArgs.isEmpty() ? null : rowArgs.get(0));
          if (src == null) return false;
          if (!types.isSameType(types.erasure(src.owner().asType()), types.erasure(srcType))) return true;
          final var claimed = writes.drop(src.name());
          if (claimed != null) {
            report(row, claimed);
          } else if (!claimedSrc.add(src.name())) {
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
                writes,
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
     * Replay construction's pairing of property {@code srcName} of {@code srcEl} with property
     * {@code tgtName} of {@code tgtEl}, whose properties are {@code sides} ({@link #classSides}).
     * The shared rules decide which types the pairing reads the two as ({@link
     * PairingRules#pairedTypes}). A value handed across unchanged is refused only where a side's
     * class fixes type arguments the value cannot be shown to fit ({@link PairingRules#fixedFit}):
     * the target, and the source too unless {@code forwardOnly}, since a mapper that runs forward
     * only never writes it. Any other pairing is decided as {@link #verifyDeep} decides it.
     */
    private void verifyProperty(
      final TypeElement srcEl,
      final TypeElement tgtEl,
      final PairSides sides,
      final String srcName,
      final String tgtName,
      final String componentName,
      final Tree at,
      final Set<String> seen,
      final boolean forwardOnly
    ) {
      final var paired = rules.pairedTypes(
        sides.source().get(srcName),
        sides.target().get(tgtName),
        sides.declaredSource().get(srcName),
        sides.declaredTarget().get(tgtName),
        srcEl.asType(),
        tgtEl.asType()
      );
      if (!paired.handedAcross()) {
        verifyDeep(paired.source(), paired.target(), componentName, at, seen, forwardOnly);
        return;
      }
      // The declared type says whether the value is copied across or shared, as the runtime
      // decides it.
      final var declared = paired.source();
      final var intoTarget = rules.fixedFit(declared, paired.sourceMember(), paired.targetMember(), componentName);
      final var intoSource = forwardOnly
        ? null
        : rules.fixedFit(declared, paired.targetMember(), paired.sourceMember(), componentName);
      if (intoTarget instanceof FixedFit.Refused<TypeMirror> refused) report(at, refused.message());
      else if (intoSource instanceof FixedFit.Refused<TypeMirror> refused) report(at, refused.message());
    }

    /**
     * Replay the shared pair decision for one field pair, recursing exactly as construction does:
     * container lifts and Optional bridges recurse on elements; reflectable pairs recurse on their
     * same-name matches, read as members of the pair's classes whatever type arguments the pair
     * gives them, since construction converts a pair given type arguments as its classes (nested
     * pairs are lenient about unmatched fields, as at runtime, but each matched nested field is
     * still checked). Cycles terminate via {@code seen}. {@code forwardOnly} holds for a {@code
     * mapperForward} site, whose enum pairs need a counterpart for each source constant only, and
     * whose source is never written.
     */
    private void verifyDeep(
      final TypeMirror srcType,
      final TypeMirror tgtType,
      final String componentName,
      final Tree at,
      final Set<String> seen,
      final boolean forwardOnly
    ) {
      // Construction converts a pair as its classes, so a type variable a member type names stands
      // for itself there, never for an argument a value may carry, and the shared decision reads
      // it the same way in both worlds.
      final var decision = rules.decidePair(srcType, tgtType, componentName, forwardOnly);
      if (decision instanceof PairDecision.Incompatible<TypeMirror> incompatible) {
        report(at, incompatible.message());
        return;
      }
      if (decision instanceof PairDecision.RecursePair) {
        final var srcEl = props.elementOf(srcType);
        final var tgtEl = props.elementOf(tgtType);
        if (srcEl == null || tgtEl == null) return;
        final var key = pairKey(srcEl, tgtEl);
        final var claimed = claimsAt(srcEl, tgtEl);
        // A nested pair no row is keyed to converts through a bridge of its own where it has one,
        // which decides its properties its own way, so a pair that may have one is left to it.
        if (claimed == null && bridgeVisible(srcEl, tgtEl)) return;
        if (!seen.add(key)) return;
        final var sides = classSides(srcEl, tgtEl);
        // A strict mapper refuses, at any pair with rows of its own, a source property no row reads
        // whose same-named target a row reading another property writes. A call with a row this
        // does not read may read the property, or be refused before the mapper is built.
        if (!forwardOnly && claimed != null && callRowsComplete) {
          final var read = sourceClaimsAt(srcEl, tgtEl);
          final var kept = new ArrayList<String>();
          for (final var name : sides.source().keySet()) if (!read.contains(name)) kept.add(name);
          final var taken = KeptNames.taken(kept, claimed);
          if (!taken.isEmpty()) {
            report(at, keptRefusal(srcEl, tgtEl, taken.getFirst()));
            return;
          }
        }
        final var match = PairingRules.matchFields(
          List.copyOf(sides.source().keySet()),
          List.copyOf(sides.target().keySet()),
          consumedAt(srcEl, tgtEl),
          claimed == null ? Set.of() : claimed
        );
        // A strict mapper refuses, at any pair with rows of its own, a target property no row
        // writes whose same-named source a row reads. A call with a row this does not read, or a
        // path row that may write below the pair it applies at, is left to construction.
        final var written = nestedWritesKnown() ? pathWritesAt(srcEl, tgtEl) : null;
        if (!forwardOnly && claimed != null && written != null) {
          for (final var name : match.unmatchedTargets()) {
            if (!sides.source().containsKey(name) || written.contains(name)) continue;
            report(
              at,
              PairingMessages.sameNameSourceRead(
                srcEl.getSimpleName().toString(),
                tgtEl.getSimpleName().toString(),
                tgtEl.getKind() == ElementKind.RECORD ? "field" : "property",
                srcEl.getKind() == ElementKind.RECORD ? "field" : "property",
                name
              )
            );
            return;
          }
        }
        for (final var name : match.matched()) {
          verifyProperty(srcEl, tgtEl, sides, name, name, name, at, seen, forwardOnly);
        }
        return;
      }
      // Below the mapper's own pair, each case of two sealed interfaces converts as a nested pair.
      if (decision instanceof PairDecision.DispatchCases) {
        final var pairs = casePairs(srcType, tgtType, forwardOnly);
        if (pairs == null) return;
        for (final var pair : pairs) {
          verifyDeep(pair.source(), pair.target(), simpleName(pair.source()), at, seen, forwardOnly);
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
     * The refusal construction gives a source property {@code name} of {@code source} that no row
     * at its pair with {@code target} reads and whose same-named target a row reading another
     * property writes. A {@code drop} given no target class that names the property is keyed to the
     * call's own target, so the refusal names the drop that applies at this pair instead.
     */
    private String keptRefusal(final TypeElement source, final TypeElement target, final String name) {
      final var sourceName = source.getSimpleName().toString();
      final var targetName = target.getSimpleName().toString();
      final var srcSlot = source.getKind() == ElementKind.RECORD ? "field" : "property";
      final var tgtSlot = target.getKind() == ElementKind.RECORD ? "field" : "property";
      for (final var drop : callTopLevelDrops) {
        if (
          types.isSameType(types.erasure(drop.source().asType()), types.erasure(source.asType())) &&
          name.equals(propertyName(drop.sourceMethod(), source))
        ) {
          return PairingMessages.keptFieldDroppedAtTop(
            sourceName,
            targetName,
            srcSlot,
            tgtSlot,
            name,
            sourceName,
            drop.sourceMethod()
          );
        }
      }
      return PairingMessages.noSameNameTarget(sourceName, targetName, srcSlot, tgtSlot, name);
    }

    /**
     * Replay construction's walk of each case pair of a call between two sealed interfaces {@code
     * sourceRoot} and {@code targetRoot}, or of a case pair of that call that is itself two sealed
     * interfaces. Each case's properties are decided as the call's own pair's are ({@link
     * #verifyOwnCase}); a case pair that is no pair of records or beans converts as a nested pair
     * does ({@link #verifyDeep}). Nothing is reported unless every row of the call was read and
     * every case is paired by a {@code cases} row.
     */
    private void verifyOwnCases(
      final Tree at,
      final TypeMirror sourceRoot,
      final TypeMirror targetRoot,
      final Set<String> seen,
      final boolean forwardOnly
    ) {
      if (!callRowsComplete) return;
      final var pairs = casePairs(sourceRoot, targetRoot, forwardOnly);
      if (pairs == null) return;
      for (final var pair : pairs) {
        final var decision = rules.decidePair(pair.source(), pair.target(), simpleName(pair.source()), forwardOnly);
        final var srcEl = props.elementOf(pair.source());
        final var tgtEl = props.elementOf(pair.target());
        if (srcEl == null || tgtEl == null) continue;
        if (decision instanceof PairDecision.DispatchCases) {
          if (seen.add(pairKey(srcEl, tgtEl))) verifyOwnCases(at, pair.source(), pair.target(), seen, forwardOnly);
        } else if (decision instanceof PairDecision.RecursePair) {
          verifyOwnCase(at, srcEl, tgtEl, seen, forwardOnly);
        } else {
          verifyDeep(pair.source(), pair.target(), simpleName(pair.source()), at, seen, forwardOnly);
        }
      }
    }

    /**
     * Replay construction's walk of one case pair of a call between two sealed interfaces, which is
     * as strict as the call's own pair: the rows keyed to the pair and those keyed to two sealed
     * interfaces its classes implement apply, and a strict mapper refuses a kept property whose
     * same-named target a row reading another property writes, a target property nothing writes,
     * and a source property nothing reads. Beside a row given a path, a constant or a compute, a
     * property nothing pairs with is left at its default, except a target property whose same-named
     * source a row reads and no row writes. A mapper that runs forward only refuses none of them.
     * Each property paired by name is checked as {@link #verifyProperty} checks it.
     *
     * <p>A case pair no row applies to that may convert through a bridge of its own, whose
     * properties are not read here, or that refuses a row keyed to two sealed interfaces is left to
     * construction.
     */
    private void verifyOwnCase(
      final Tree at,
      final TypeElement srcEl,
      final TypeElement tgtEl,
      final Set<String> seen,
      final boolean forwardOnly
    ) {
      final var sourceType = types.erasure(srcEl.asType());
      final var targetType = types.erasure(tgtEl.asType());
      final var claimedOrNull = claimsAt(srcEl, tgtEl);
      // A case pair no row applies to converts through a bridge of its own where it has one.
      if (claimedOrNull == null && bridgeVisible(srcEl, tgtEl)) return;
      if (!rules.reflectable(sourceType) || !rules.reflectable(targetType)) return;
      if (!seen.add(pairKey(srcEl, tgtEl))) return;
      final var own = rowsKeyedTo(callRows, sourceType, targetType);
      final var inherited = walk.inherited(callRows, own, sourceType, targetType);
      if (!inherited.refused().isEmpty()) return;
      // A row given a path, a constant or a compute turns the pair lenient about the properties
      // nothing pairs with.
      var lenientRows = false;
      for (final var row : own) lenientRows |= row.kind() == PairWalk.RowKind.PATH;
      for (final var row : inherited.applied()) lenientRows |= row.kind() == PairWalk.RowKind.PATH;
      final var written = nestedWritesKnown() ? pathWritesAt(srcEl, tgtEl) : null;
      final var sides = classSides(srcEl, tgtEl);
      final Set<String> claimed = claimedOrNull == null ? Set.of() : claimedOrNull;
      final var read = sourceClaimsAt(srcEl, tgtEl);
      final var srcSimple = srcEl.getSimpleName().toString();
      final var tgtSimple = tgtEl.getSimpleName().toString();
      final var srcSlot = srcEl.getKind() == ElementKind.RECORD ? "field" : "property";
      final var tgtSlot = tgtEl.getKind() == ElementKind.RECORD ? "field" : "property";
      if (!forwardOnly) {
        final var kept = new ArrayList<String>();
        for (final var name : sides.source().keySet()) if (!read.contains(name)) kept.add(name);
        final var taken = KeptNames.taken(kept, claimed);
        if (!taken.isEmpty()) {
          report(at, keptRefusal(srcEl, tgtEl, taken.getFirst()));
          return;
        }
      }
      final var match = PairingRules.matchFields(
        List.copyOf(sides.source().keySet()),
        List.copyOf(sides.target().keySet()),
        consumedAt(srcEl, tgtEl),
        claimed
      );
      final var matched = new HashSet<>(match.matched());
      for (final var name : sides.target().keySet()) {
        if (claimed.contains(name)) continue;
        if (matched.contains(name)) {
          verifyProperty(srcEl, tgtEl, sides, name, name, name, at, seen, forwardOnly);
          continue;
        }
        if (forwardOnly) continue;
        final var namesake = sides.source().containsKey(name);
        if (lenientRows && !(namesake && written != null && !written.contains(name))) continue;
        report(
          at,
          namesake
            ? PairingMessages.sameNameSourceRead(srcSimple, tgtSimple, tgtSlot, srcSlot, name)
            : PairingMessages.noSameNameSource(srcSimple, tgtSimple, tgtSlot, srcSlot, name)
        );
        return;
      }
      if (forwardOnly || lenientRows) return;
      for (final var name : match.unmatchedSources()) {
        if (read.contains(name)) continue;
        report(at, PairingMessages.noSameNameTarget(srcSimple, tgtSimple, srcSlot, tgtSlot, name));
        return;
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
    return props.propertyTypes(type instanceof DeclaredType dt ? dt : null, element);
  }

  /**
   * The same properties as {@link #propertiesOf}, each typed as its accessor declares it rather
   * than as a member of the class, so a type a generic superclass leaves to a variable stays that
   * variable.
   */
  private Map<String, TypeMirror> declaredPropertiesOf(final TypeElement element) {
    return props.propertyTypes(null, element);
  }

  /**
   * The properties of the pair of {@code source} and {@code target} as construction reads them: as
   * members of each class with every type variable the class declares kept as that variable, and as
   * declared. Construction converts a pair as its classes, whatever type arguments a property or a
   * class literal gives them, so a variable's bound says what a side holds.
   */
  private PairSides classSides(final TypeElement source, final TypeElement target) {
    return new PairSides(
      propertiesOf(source.asType(), source),
      propertiesOf(target.asType(), target),
      declaredPropertiesOf(source),
      declaredPropertiesOf(target)
    );
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
