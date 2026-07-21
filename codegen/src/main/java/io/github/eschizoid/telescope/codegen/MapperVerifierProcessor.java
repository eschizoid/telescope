package io.github.eschizoid.telescope.codegen;

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
import io.github.eschizoid.telescope.internal.pairing.PairDecision;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
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
 * shape gate, and this verifier never fires where construction succeeds.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions({ "telescope.verify", "telescope.verify.verbose" })
public final class MapperVerifierProcessor extends AbstractProcessor {

  private static final String TELESCOPE_FQN = "io.github.eschizoid.telescope.Telescope";
  private static final String MAPPING_FQN = "io.github.eschizoid.telescope.mapping.Mapping";
  private static final String WRITE_HINT_FQN = "io.github.eschizoid.telescope.mapping.WriteHint";
  private static final String NULL_HINT_FQN = "io.github.eschizoid.telescope.mapping.NullHint";

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
   * One statically-recognized accessor reference: normalized property name, its declared type (as a
   * member of the accessor's own class), and that owning class. The owner matters because rows may
   * be keyed to NESTED pairs — {@code to(UserEntity::name, UserDto::fullName)} inside {@code
   * map(CompanyEntity, CompanyDto, ...)} — which the runtime groups by the accessors' classes; such
   * rows claim nothing at the call's top-level pair.
   */
  private record Prop(String name, TypeMirror type, TypeElement owner) {}

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
        if (!MAPPING_FQN.equals(ownerFqn)) {
          analyzable = false;
          continue;
        }
        analyzable &= verifyRow(row, rowExec, srcType, tgtType, srcSimple, tgtSimple, claimedSrc, claimedTgt);
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
          verifyDeep(srcProps.get(name), tgtProps.get(name), name, node, seen);
        }
      }
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
      final Set<String> claimedTgt
    ) {
      final var rowName = rowExec.getSimpleName().toString();
      final var rowArgs = row.getArguments();
      switch (rowName) {
        case "to", "toOrElse", "toOrElseGet", "toOneWay", "enumTo", "via" -> {
          final var src = accessorProp(rowArgs.isEmpty() ? null : rowArgs.get(0));
          final var tgt = rowArgs.size() < 2 ? null : accessorProp(rowArgs.get(1));
          if (src == null || tgt == null) return false;
          // Rows are grouped by the accessors' OWN classes at construction (a row may be keyed to
          // a nested pair encountered during recursion). Only rows keyed to THIS call's pair claim
          // fields here; nested-keyed rows are still shape-checked against their own types below.
          // Both sides erased: a generic owner's asType() is the parameterized prototype, which
          // isSameType would never match against the raw class literal.
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
            verifyDeep(src.type(), tgt.type(), src.name() + " → " + tgt.name(), row, seen);
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
              return verifyRow(inner, innerExec, srcType, tgtType, srcSimple, tgtSimple, claimedSrc, claimedTgt);
            }
          }
          return false;
        }
        case "constant", "compute" -> {
          // Accessor-form rows put the runtime into permissive mode (handled by the caller); the
          // telescope-form has no statically-recoverable target either way. Claims are irrelevant
          // because permissive mode disables completeness.
          return true;
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
     * matched nested field is still shape-checked). Cycles terminate via {@code seen}.
     */
    private void verifyDeep(
      final TypeMirror srcType,
      final TypeMirror tgtType,
      final String componentName,
      final Tree at,
      final Set<String> seen
    ) {
      // Wildcards and type variables aren't statically comparable across the two worlds — the
      // runtime's structural Type#equals calls identical wildcard pairs equal where isSameType is
      // specified false. Skip rather than mis-decide: the construction backstop still applies.
      if (!staticallyComparable(srcType) || !staticallyComparable(tgtType)) return;
      final var decision = rules.decidePair(srcType, tgtType, componentName);
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
          verifyDeep(srcProps.get(name), tgtProps.get(name), name, at, seen);
        }
        return;
      }
      if (decision instanceof PairDecision.OptionalToNullable<TypeMirror> d) {
        verifyDeep(d.elementSrc(), d.elementTgt(), componentName + "[*]", at, seen);
        return;
      }
      if (decision instanceof PairDecision.NullableToOptional<TypeMirror> d) {
        verifyDeep(d.elementSrc(), d.elementTgt(), componentName + "[*]", at, seen);
        return;
      }
      if (decision instanceof PairDecision.LiftContainer<TypeMirror> d) {
        verifyDeep(d.src().elementType(), d.tgt().elementType(), componentName + "[*]", at, seen);
      }
    }

    /** True when {@code t} contains no wildcard or type variable at any depth. */
    private boolean staticallyComparable(final TypeMirror t) {
      if (t.getKind() == TypeKind.WILDCARD || t.getKind() == TypeKind.TYPEVAR) return false;
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

    /** A method-reference accessor resolved to its normalized property name + declared type. */
    private Prop accessorProp(final ExpressionTree arg) {
      if (!(arg instanceof MemberReferenceTree)) return null;
      final var path = TreePath.getPath(getCurrentPath().getCompilationUnit(), arg);
      if (path == null) return null;
      if (!(trees.getElement(path) instanceof ExecutableElement accessor)) return null;
      if (!(accessor.getEnclosingElement() instanceof TypeElement owner)) return null;
      final var name = normalize(accessor.getSimpleName().toString());
      // Resolve the return type against the accessor's OWN class — a row may be keyed to a nested
      // pair, so the call's top-level types are the wrong receiver for asMemberOf.
      final var type =
        owner.asType() instanceof DeclaredType dt
          ? ((ExecutableType) types.asMemberOf(dt, accessor)).getReturnType()
          : accessor.getReturnType();
      return new Prop(name, type, owner);
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

  /**
   * Enumerate the pairing-relevant properties of a record (components, declaration order) or a bean
   * (public zero-arg {@code getX} / {@code isX} getters — skipping {@code Object}, platform ({@code
   * java.*} / {@code jdk.*}) supertypes, and the {@code class} pseudo-property — mirroring the
   * runtime's getter scan).
   */
  private Map<String, TypeMirror> propertiesOf(final TypeMirror type, final TypeElement element) {
    final var result = new LinkedHashMap<String, TypeMirror>();
    final var declared = type instanceof DeclaredType dt ? dt : null;
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
