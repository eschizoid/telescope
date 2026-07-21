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

| Capability                        | Substrate mechanic under test                                                                            |
| --------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Record field update               | `SerializedLambda` method-reference decode + LMF record reader + cached canonical-ctor `MethodHandle`    |
| Record read (`.read()`)           | pure LMF-dispatched record component read (the `Records.read` path through the public surface)           |
| Bean read (`.read()`)             | LMF-dispatched bean getter `Function` (`ofBean(...).field(getter).read()`, the `Beans` read path)        |
| Runtime record → record `mapper`  | LMF record readers on the source, canonical-constructor rebuild, carrying a nested record + enum         |
| Runtime record → bean `mapper`    | LMF getter readers + no-arg-ctor `Supplier` + LMF setter `BiConsumer` writers (the `Beans` path)         |
| Runtime record → builder `mapper` | the `Beans` `BuilderWriter` — `builder()` `Supplier` + fluent-setter `BiFunction` + `build()` `Function` |
| Generated `@FromMap`              | reflection-free codegen `Map → record` converter — the control (no LMF, no `SerializedLambda`)           |
| Generated `@Bridge` constant      | the emitted `AccountBridge.BRIDGE` (`Telescope<Account, AccountEntity>`) baked into the image heap       |

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

## Two walls, and how each falls

The codegen path (`@Bridge` / `@FromMap`) native-images with zero reflection config — it compiles to typed method calls.
The runtime reflective path hit two walls under native-image; both are now cleared.

**Wall B — runtime `LambdaMetafactory` (the load-bearing one).** `Records` / `Beans` build one accessor `Function` per
member by calling `LambdaMetafactory.metafactory(...)` **at run time**, which synthesizes a class per accessor —
native-image's closed world forbids defining a class at run time (`Classes cannot be defined at runtime`). Fixed **in
the substrate**: `internal/NativeImage.IN_IMAGE` (a JDK-only `org.graalvm.nativeimage.imagecode` system-property check,
no GraalVM dependency) branches the accessor builders to a `MethodHandle.asType` closure inside an image — the same
class-definition-free shape `Records.buildCtorFn` already used for the canonical constructor, centralized in
`internal/MhAccessors` (one closure per SAM shape). Every runtime rebuild strategy is covered: record reads, the
no-arg-constructor + setter + getter paths, and the whole `BuilderWriter` path (`builder()` → fluent setters →
`build()`), so mapping to an immutable `@Builder` target works too. The JVM keeps the `LambdaMetafactory` hot path
unchanged; the branch is one folded `static final boolean`. This ships in `telescope-core`, so the runtime path is
AOT-capable out of the box. (The one exception is the optional Hibernate-proxy accessor path, which stays
JVM/codegen-only under AOT.)

**Wall A — `SerializedLambda` decode.** `.field(User::name)` recovers the field name by invoking the method reference's
`writeReplace()` to read a `SerializedLambda`. native-image only synthesizes `writeReplace()` for a lambda whose
capturing class is registered as a lambda-capturing type for serialization. Fixed with an app-level
`serialization-config.json` naming the call-site class (here, `NativeVerify`). `.fieldByName(String)` and codegen
navigators are the `SerializedLambda`-free alternatives that need no such registration.

## What ships where

- **Build-time init — `telescope-core`'s own `native-image.properties`.** The `@Bridge` / `@FromMap` codegen constants
  (e.g. `AccountBridge.BRIDGE`, a `Telescope` built at the generated class's static init) land in the build-time image
  heap; native-image defaults every class to run-time init, and a heap object of a run-time-init type is a hard error.
  telescope-core ships `--initialize-at-build-time` for the telescope packages in its jar, so **adopters need no build
  args for telescope itself**. The example still initializes its own generated-model package (app-specific).
- **`--no-fallback`** — in the example build: fail rather than silently emit a JVM-fallback image, so a reachability gap
  is a hard error, not a slow "native" binary that is really the JVM.
- **App-level reachability metadata — the app's own types.** The runtime reflective mapper walks its source/target types
  with `Class.getRecordComponents()` and unreflects their accessors / constructor / setters, so those DTO types need a
  `reflect-config.json`; `.field(methodref)` needs the call-site class in a `serialization-config.json` (Wall A). Both
  live under the app's `META-INF/native-image/...`, exactly as every GraalVM app ships for its own domain types — the
  example does this for its model types (including the builder-only bean and its `Builder`). Four ways to produce them,
  in order of how often they fit — see below.
- **`privateLookupIn`.** All records/beans here are public on the classpath, so `MethodHandles.privateLookupIn` succeeds
  without an `opens` directive. A downstream consumer with JPMS-closed packages still needs
  `opens ... to io.github.eschizoid.telescope;` — a module-descriptor requirement, not a native-image one.

## Producing the app metadata

1. **Hand-write it.** For a handful of DTO types this is the honest option: the example's
   [`reflect-config.json`](../examples/graphql/src/main/resources/META-INF/native-image/io.github.eschizoid/telescope-examples-graphql/reflect-config.json)
   is eight readable entries and doubles as the template. One gotcha: nested types get their own entry with the binary
   name (`AccountBuilderBean$Builder`) — the builder write path unreflects the builder class too.
2. **Framework hints — the route for the starter users.** Both frameworks telescope ships starters for generate this
   metadata themselves; if you're on one, use its mechanism and skip the JSON entirely:
   - **Quarkus**: `@RegisterForReflection(targets = { User.class, UserView.class })` on any class in the app (or
     `classNames` for types you can't reference). Quarkus emits the config during the native build.
   - **Spring Boot (AOT)**: `@RegisterReflectionForBinding(User.class)` on a `@Configuration` class, or a
     `RuntimeHintsRegistrar` that loops your model package and calls `hints.reflection().registerType(...)` per type.
     Spring's AOT engine writes the config during `bootBuildImage`.
3. **Generate from the model package.** For a large plain-Java model, a small Gradle task can emit the entries from the
   compiled classes directory. Walk **compiled classes, not sources** — nested types compile to their own `.class` files
   (`AccountBuilderBean$Builder.class`), so the classes walk catches them where a source walk silently misses them:

   ```kotlin
   tasks.register("generateReflectConfig") {
       dependsOn(tasks.compileJava)
       val pkg = "com.acme.app.model"
       val classesDir = layout.buildDirectory.dir("classes/java/main/" + pkg.replace('.', '/'))
       val out = layout.buildDirectory.file("generated/native-config/reflect-config.json")
       inputs.dir(classesDir)
       outputs.file(out)
       doLast {
           val entries = classesDir.get().asFile.walkTopDown()
               .filter { it.extension == "class" }
               .map { "$pkg.${it.nameWithoutExtension}" }
               .sorted()
               .joinToString(",\n") {
                   """  { "name": "$it", "allDeclaredConstructors": true, "allDeclaredMethods": true, "allDeclaredFields": true }"""
               }
           out.get().asFile.apply {
               parentFile.mkdirs()
               writeText("[\n$entries\n]\n")
           }
       }
   }

   graalvmNative {
       binaries {
           named("main") {
               buildArgs.add("-H:ConfigurationFileDirectories=${layout.buildDirectory.dir("generated/native-config").get()}")
           }
       }
   }
   ```

   It over-registers (every type in the package, all members) — the price of not enumerating by hand. Wire the task
   before `nativeCompile` and keep the package boundary tight.

4. **The GraalVM tracing agent.** Run the app or its tests once on the JVM with
   `-agentlib:native-image-agent=config-output-dir=...` and it dumps all the config files from observed behavior.
   Broadest coverage, noisiest output — entries for everything the run touched, not just your model — and it only
   records what actually executed, so an untested code path is an unregistered one.

## Verdict

**Runtime and codegen both work under GraalVM native-image.** All eight verifier capabilities — record field update,
record read, bean read, the runtime record→record / record→bean / record→builder-bean mappers, `@FromMap`, and `@Bridge`
— build and run in the native binary, confirmed by `.github/workflows/native-image.yaml`. Telescope's runtime reflective
mapper is AOT-capable out of the box for every rebuild strategy (records, no-arg-ctor + setters, immutable `@Builder`
targets); Wall B is fixed in `telescope-core`, and the adopter supplies only the standard reachability metadata for
their own types (`reflect-config.json` for runtime-mapper DTOs, `serialization-config.json` for `.field(methodref)`
call-site classes). The codegen path (`@Focus`/`@BeanFocus`/`@Bridge`/`@FromMap`) needs none of that — it is
reflection-free and native-images with zero config. The one runtime path still JVM/codegen-only under AOT is the
Hibernate-proxy accessor. The workflow re-checks the whole surface on every push to `main` and weekly against GraalVM
updates, so a regression in any capability turns the job red.

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
