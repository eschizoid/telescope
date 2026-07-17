# telescope on GraalVM native-image

Telescope's runtime hot path is reflection-free by design: field reads and record/bean rebuilds ride a per-class cache
of `LambdaMetafactory`-built `Function` / `Supplier` / `BiConsumer` instances (see `internal/Records.java`,
`internal/Beans.java`), and `.field(Record::accessor)` recovers the field name from a `Serializable` method reference
via `SerializedLambda` decode. The codegen `@Bridge` / `@FromMap` paths emit typed converters with no runtime
reflection. GraalVM's closed-world assumption can break exactly these mechanics — synthetic LMF classes, `invokedynamic`
bootstrap, `SerializedLambda` deserialization, `MethodHandles.privateLookupIn`, and codegen constants baked into the
image heap.

The `:examples:graphql` example carries the verification: its `NativeVerify` main exercises the full runtime + codegen
substrate over the example's own domain models and either lets the project claim native support or names the real gap.
It doubles as a runnable demo — the same module also serves the runtime (`Telescope.fromMap`) and generated (`@FromMap`)
GraphQL servers.

## What the verifier covers

`examples/graphql/src/main/java/.../server/NativeVerify.java` exercises, in one process, the substrate mechanics whose
survival under native-image is what we verify. Each capability prints `PASS` / `FAIL` and the process exits non-zero on
any failure — so `native-image` compiling and running the binary **is** the test.

| Capability                       | Substrate mechanic under test                                                                         |
| -------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Record field update              | `SerializedLambda` method-reference decode + LMF record reader + cached canonical-ctor `MethodHandle` |
| Record read (`.read()`)          | pure LMF-dispatched record component read (the `Records.read` path through the public surface)        |
| Bean read (`.read()`)            | LMF-dispatched bean getter `Function` (`ofBean(...).field(getter).read()`, the `Beans` read path)     |
| Runtime record → record `mapper` | LMF record readers on the source, canonical-constructor rebuild, carrying a nested record + enum      |
| Runtime record → bean `mapper`   | LMF getter readers + no-arg-ctor `Supplier` + LMF setter `BiConsumer` writers (the `Beans` path)      |
| Generated `@FromMap`             | reflection-free codegen `Map → record` converter — the control (no LMF, no `SerializedLambda`)        |
| Generated `@Bridge` constant     | the emitted `AccountBridge.BRIDGE` (`Telescope<Account, AccountEntity>`) baked into the image heap    |

`Records` / `Beans` live in `:internal`, JPMS-sealed to `:core`, so the verifier cannot call them directly. It reaches
the identical LMF call sites through the public `Telescope.of(...).field(...).read()` /
`Telescope.mapper(...).forward()` surface — same machinery, honest module boundary.

## How to run it

- **JVM sanity check** (validates the harness logic, not native-image): `./gradlew :examples:graphql:runNativeVerify`
- **Native build + run** (the real test, needs a GraalVM `native-image` on `PATH`):
  `./gradlew :examples:graphql:nativeRun`

CI wiring: `.github/workflows/native-image.yaml` installs GraalVM via `graalvm/setup-graalvm`, then runs
`:examples:graphql:nativeRun`. The job fails if the binary exits non-zero. It runs on `workflow_dispatch`, on pushes to
`main` that touch the example or the substrate modules, and weekly (to catch a GraalVM-version regression on its own
cadence). Native builds are minutes-long, so it deliberately does **not** gate every PR.

## GraalVM config the substrate needs

Two build args, both in `examples/graphql/build.gradle.kts`:

- **`--no-fallback`** — fail rather than silently emit a JVM-fallback image, so a reachability gap is a hard error, not
  a slow "native" binary that is really the JVM.
- **`--initialize-at-build-time=...`** for the telescope classes (`io.github.eschizoid.telescope`,
  `...telescope.internal`, `...telescope.internal.optics`) and the generated model package
  (`...examples.graphql.model`). **This is the load-bearing arg.** Without it a native-image build of the `@Bridge`
  capability fails — the earlier native-image build of this exact mechanic surfaced:

  ```
  UnsupportedFeatureException: An object of type 'io.github.eschizoid.telescope.Telescope$BridgeTelescope'
  was found in the image heap. This type, however, is marked for initialization at image run time...
  ```

  The `@Bridge`-generated `AccountBridge.BRIDGE` constant is a `Telescope` instance built at the generated class's
  static init, so it lands in the build-time image heap; native-image defaults every class to run-time init, and a heap
  object of a run-time-init type is a hard error. Initializing the telescope + generated-model classes at build time
  bakes the constant safely. These classes are stateless optic wrappers over `MethodHandle`/`Function` fields — no
  per-instance mutable or environment-dependent state — so build-time init is safe.

On the other mechanics, the expectation is that no `reflect-config.json` / `serialization-config.json` is needed — the
first green CI run is what confirms it:

- **LMF / `invokedynamic`.** Telescope builds its readers/writers by calling `LambdaMetafactory.metafactory(...)`
  **explicitly at runtime** over `MethodHandle`s to accessors/constructors the verifier reaches through concrete,
  statically-reachable types. native-image folds those synthetic `Function`/`Supplier`/`BiConsumer` classes when the
  target member is reachable — no `Method.invoke`, so no reflection registration is implied.
- **`SerializedLambda`.** `.field(User::name)` decodes a `Serializable` method reference. The record-field-update and
  read capabilities exercise this; the decode targets concrete, statically-reachable accessors, so no
  `serialization-config.json` entry is expected. If a native-image run regresses this, the failing capability line + the
  uploaded build report will name the registration.
- **`privateLookupIn`.** All records/beans here are public on the classpath, so `MethodHandles.privateLookupIn` succeeds
  without an `opens` directive. A downstream consumer with JPMS-closed packages still needs
  `opens ... to io.github.eschizoid.telescope;` — a module-descriptor requirement, not a native-image one.

## Verdict

**Pending the first green CI run.** What is confirmed today: the JVM run of `NativeVerify` (`runNativeVerify`) passes all
seven capabilities, so the harness and its assertions are sound. On the native-image side, the one requirement found so
far is the `--initialize-at-build-time` config above — the codegen `@Bridge`/`@FromMap` constant classes need build-time
init because the constants live in the image heap. The expectation is that the seven capabilities then build and run
natively with no further config, but the standing verdict is the workflow's actual green native-image run, not this
prediction. This section will be updated to a firm "works" (or to name whatever additional config the run demands) once
`.github/workflows/native-image.yaml` has its first green run — it fires on the next push to `main` and weekly
thereafter against GraalVM updates.

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
