# telescope on GraalVM native-image

Telescope's runtime hot path is reflection-free by design: field reads and record/bean rebuilds ride a per-class cache
of `LambdaMetafactory`-built `Function` / `Supplier` / `BiConsumer` instances (see `internal/Records.java`,
`internal/Beans.java`), and `.field(Record::accessor)` recovers the field name from a `Serializable` method reference
via `SerializedLambda` decode. GraalVM's closed-world assumption can break exactly these mechanics — synthetic LMF
classes, `invokedynamic` bootstrap, `SerializedLambda` deserialization, and the `MethodHandles.privateLookupIn` path.

The `:native-smoke` module is a self-contained harness that either lets the project claim native support or names the
real gap.

## What the smoke test covers

`native-smoke/src/main/java/.../NativeSmokeMain.java` exercises, in one process, the substrate mechanics whose survival
under native-image was unverified. Each capability prints `PASS` / `FAIL` and the process exits non-zero on any failure
— so `native-image` compiling and running the binary **is** the test.

| Capability                  | Substrate mechanic under test                                                                         |
| --------------------------- | ----------------------------------------------------------------------------------------------------- |
| Record field update         | `SerializedLambda` method-reference decode + LMF record reader + cached canonical-ctor `MethodHandle` |
| Record → record `mapper`    | LMF record readers on the source, canonical-constructor rebuild on the target                         |
| Bean → bean `mapper`        | LMF getter readers + no-arg-ctor `Supplier` + LMF setter `BiConsumer` writers                         |
| LMF record read (`.read()`) | pure LMF-dispatched record component read (the `Records.read` path through the public surface)        |
| LMF bean read (`.read()`)   | pure LMF-dispatched bean getter read (the `Beans.readProperty` path through the public surface)       |
| Codegen `@Bridge` constant  | the generated `SmokeBeanABridge.BRIDGE` — typed method calls, **no** runtime reflection (control)     |

The `@Bridge` row is the control: the codegen path uses no runtime reflection, so it should always survive. The other
five rows are the ones that stress the reflective LMF / `SerializedLambda` substrate. (`:examples:graphql` already
proves the codegen `@FromMap` path native-images with `--no-fallback`; this module is the missing coverage for the
_reflective_ tier.)

`Records` / `Beans` live in `:internal`, JPMS-sealed to `:core`, so the smoke module cannot call them directly. It
reaches the identical LMF call sites through the public `Telescope.of(...).field(...).read()` /
`Telescope.mapper(...).forward(...)` surface — same machinery, honest module boundary.

## How to run it

- **JVM sanity check** (validates the harness logic, not native-image): `./gradlew :native-smoke:run`
- **Native build + run** (the real test, needs a GraalVM `native-image` on `PATH`): `./gradlew :native-smoke:nativeRun`

CI wiring: `.github/workflows/native-image.yaml` installs GraalVM via `graalvm/setup-graalvm`, then runs
`:native-smoke:nativeRun`. The job fails if the binary exits non-zero. It runs on `workflow_dispatch`, on pushes to
`main` that touch the substrate modules, and weekly (to catch a GraalVM-version regression on its own cadence). Native
builds are minutes-long, so it deliberately does **not** gate every PR.

## GraalVM config the substrate required

**None, as configured.** The build uses `--no-fallback` (fail rather than silently emit a JVM-fallback image) and
`--initialize-at-build-time` scoped to the smoke module's own package. No `reflect-config.json`,
`serialization-config.json`, or `resource-config.json` was wired, on the following reasoning (see the verdict caveat
below):

- **LMF / `invokedynamic`.** Telescope builds its readers/writers by calling `LambdaMetafactory.metafactory(...)`
  **explicitly at runtime** (not via a compiler-emitted `invokedynamic` bootstrap). native-image supports runtime
  `LambdaMetafactory` calls: the synthetic `Function`/`Supplier`/`BiConsumer` implementation classes are generated and
  registered during image build as long as the target method (the record accessor, bean getter/setter, constructor) is
  itself reachable — and it is, because the smoke `main` calls each accessor/constructor through concrete, statically
  reachable types. No `reflect-config` entry is needed for the accessors because they are invoked through `MethodHandle`
  call sites the analysis can see, not through `Method.invoke`.
- **`SerializedLambda`.** `.field(User::name)` decodes a `Serializable` lambda. This is the one genuinely at-risk
  mechanic: lambda deserialization historically needed a `serialization-config.json` entry (or the `$deserializeLambda$`
  method to be reachable). Recent GraalVM registers the `altMetafactory`-generated lambda classes and their
  `writeReplace`/`$deserializeLambda$` automatically when the lambda is created in reachable code, which the smoke
  `main`'s method references are. **If any config turns out to be needed, this is the row that will demand it** — and
  the smoke test is precisely what surfaces that.
- **`privateLookupIn`.** All records/beans in the smoke module are public and in the same (open, unnamed-on-classpath)
  module, so `MethodHandles.privateLookupIn` succeeds without an `opens` directive. A downstream consumer with
  JPMS-closed packages still needs `opens ... to io.github.eschizoid.telescope;` — that is a module-descriptor
  requirement, not a native-image one.

## Verdict

**Correct-by-construction, not yet CI-verified — local `native-image` was unavailable (OpenJDK 25 only on the build
host), so the native binary has not actually been produced here.** The JVM run of the identical `main` passes all six
capabilities, which proves the harness logic; the native verdict lands the first time the workflow runs on a
GraalVM-equipped runner.

Best current assessment from the LMF/`SerializedLambda` mechanics: the five reflective capabilities **should** survive
on a modern GraalVM (24+) with **zero** reachability config, because telescope calls `LambdaMetafactory` at runtime over
handles to statically-reachable accessors rather than going through `Method.invoke`, and the method references are
created in reachable code. The single most likely place to need config is `SerializedLambda` deserialization for
`.field(MethodRef)`; if the workflow's first run reds out, the failing capability line + the uploaded native-image build
report will name the exact registration to add (expected shape: a `serialization-config.json` entry for the lambda's
declaring class, or `-H:+ReportExceptionStackTraces` pointing at the `$deserializeLambda$` gap). Update this section
with the real finding once the job has run.

## Appendix — feasibility of shading `:internal` into `:core` at publish

Today the internal optic lattice ships as a separate `telescope-internal` JPMS module whose packages are
qualified-exported only to `io.github.eschizoid.telescope`; a downstream `import ...internal.optics.Lens` fails to
compile with "package is not visible". The question is whether we could instead **shade** `:internal`'s bytecode into
`telescope-core` at publish time so consumers physically cannot reference the lattice types.

Short answer: a Gradle `com.gradleup.shadow` (shadow-jar) merge is mechanically possible but **fights the JPMS model and
is not worth it** — the current qualified-export already delivers the exact "users cannot type `Lens`" guarantee, at
compile time, for free. A shadow jar merges class files but does not merge module descriptors: `:core` and `:internal`
each carry a `module-info.java`, and a fat jar can hold at most one module descriptor, so the merged artifact would have
to either drop to the classpath (losing the JPMS boundary that is the whole encapsulation mechanism) or hand-author a
single fused descriptor that re-exports nothing internal (doable, but now the lattice types live in `telescope-core`'s
own unexported packages — encapsulated by _package non-export_ rather than _qualified export_, a lateral move, not a
win). It also breaks the published `telescope-internal` coordinate that the release list and JReleaser staging already
depend on, breaks the `--add-exports` seam the `:benchmarks` module uses to test internals, and complicates
source/javadoc jars. A source-merge (physically relocating `internal/*` sources under `core/src`) is even worse: it
erases the two-layer module boundary the architecture is built on and the qualified-export test guarantee with it. Net:
keep the qualified-export boundary; shading buys no additional encapsulation and costs the published-module contract.
