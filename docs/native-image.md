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
| Runtime `fromMap` with a binder   | `Telescope.fromMap(...)` finding the generated `AddressFromMap` binder through its `ServiceLoader` entry |
| Generated `@Bridge` constant      | the emitted `AccountBridge.BRIDGE` (`Telescope<Account, AccountEntity>`) baked into the image heap       |
| Generated `@Focus` navigator      | `UserTelescope.of().address().city()` read and update, with the navigator in `serialization-config.json` |
| Navigator-only `Telescope.all`    | fused multi-edit over generated navigator paths, on records with no `reflect-config.json` entry          |
| Row typed by a type argument      | `Mapping.to` rows typed by `Tagged<String>` and `Tagged<Badge>`, loading each class by name to check it  |
| `@Bridge` mapper field            | `TicketMappers.TICKET` found by `StackWalker` and `ServiceLoader`, over records with no registration     |

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
- **Generated `@FromMap` binder discovery — nothing to configure.** A runtime `fromMap` fills a component whose type has
  a generated binder by finding the binder's `FromMapProvider` through `ServiceLoader` and calling the binder it
  returns. The processor writes the `META-INF/services` registration beside the binder, and native-image includes the
  providers that registration lists, so no reflection entry is needed for the binder or its provider. An image built
  from named modules reads `provides` directives instead, so declare each binder's `<Name>FromMap.Provider` there, and a
  fat jar fed to native-image must merge the service files of the jars it combines (`ServicesResourceTransformer`,
  `mergeServiceFiles()`).
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
  `opens ... to io.github.eschizoid.telescope.internal;` — a module-descriptor requirement, not a native-image one.

## A container field whose class the runtime does not name

The reflective deep-map allocates a rebuilt container from a table of the JDK containers it knows. A declared type
outside that table is reached instead through a public-lookup constructor handle, and under native image that
constructor needs a `reflect-config` entry like any other reflective target. On the JVM it needs nothing, so this is a
difference the JVM run will not show you.

Every container the table names — `List`, `Set`, `Map`, their sorted and concurrent interfaces, `ArrayList`,
`LinkedHashSet`, `LinkedHashMap`, `TreeMap` and the rest — is allocated by a direct `new` when the deep-map route
reaches it, and needs no metadata at all.

The table is not the only route, though, and two others have no table in front of them. A container component whose
declared type carries no type arguments never yields a container view, so it is copied element-wise instead; and the
pairing gate probes whether such a copy can be built. Both ask `Beans.intermediateAllocator`, which reaches a public
constructor through the same public lookup whenever the declaring module declines `privateLookupIn` — which every named
platform module does. So a `Properties`, `ArrayList` or `LinkedHashMap` reached by one of those two routes needs a
`reflect-config` entry even though the table names it.

The default tree does not reach them: it refuses to materialise a platform type as a default at all, so the only
constructor it can want through the public lookup belongs to an adopter's own class in a module that declines `opens`.

The rule that covers both: if a container class is constructed by anything other than the deep-map table, register its
no-argument constructor. Registering the handful your model actually declares costs nothing if it turns out the table
covered them.

What reaches the handle is a declared type the table does not name and whose family default cannot stand in for it: a
concrete JDK container outside the list, or a container class of the adopter's own. Give such a class an entry for its
no-argument constructor, the same way a DTO gets one.

A container class of the adopter's own with no no-argument constructor, only one taking the container it copies, is
built through that one-argument constructor where its elements pass through unchanged. Register that constructor
instead, with the parameter type it declares.

The failure mode if you do not is worth knowing, because it is deliberately not softened. Under exact reachability
metadata the image raises a missing-registration error naming the class it was built without, and telescope lets that
through rather than converting it into its own refusal that the class has no constructor a rebuild can call. That
refusal's advice — declare a constructor, or supply an explicit conversion row — is wrong here: the type is fine and the
program works on the JVM. Only the image is missing something, and only the registration error says so.

## A mapper a `@Bridge` field declares

`Telescope.mapper(...)` and `mapperForward(...)` read their calling class once, with `StackWalker`, and look up the
bridge a `@Bridge` field of that class declares for the pair through `ServiceLoader`. Both work in an image: the image
builder registers the providers in `META-INF/services`, and the caller class needs no registration. The field's rows are
built but never decoded, so the method references they name need no `serialization-config.json` entry, except a
`constant` or `compute` row, which decodes its target accessor when it is built. The mapper then runs the generated
bridge, which is typed method calls.

A class compiled without the javac tree API, by Eclipse's compiler for one, has its rows read by no processor, and its
mapper is built on the runtime path, which needs the registrations a runtime mapper needs. The processor marks such a
class with a resource that `explain()` reads to say why; include `META-INF/telescope/**` in the image's resources to
keep that reason.

## A nested pair's own bridge

A runtime mapper converts a nested pair that has its own `@Bridge` through the bridge that annotation generated, and a
`mapperForward` with no steps does the same for its own pair. It finds the bridge by name: it loads
`<Source>To<Target>Bridge`, the name each bridge of a source declaring several targets takes, and then `<Source>Bridge`
from the source's package with `Class.forName`, and reads its `BRIDGE` field. Whether a long-form bridge is one a
`@Bridge` declared is a method call on the bridge, which needs no registration. A carrier bridge is found through
`ServiceLoader` instead.

In an image, a holder class that is not registered reads as absent. The mapper then walks the pair by name, so the
bridge's transforms, renames and other settings are not applied, and the result differs from the JVM's without an error.
Register each `<Source>Bridge` and `<Source>To<Target>Bridge` a runtime mapper can reach: the class, and its `BRIDGE`
field for reading. Keep the `META-INF/services` entries the processor writes for carrier bridges, as for any other
service.

The mapper looks up both names for every nested pair it reaches, and for most pairs neither class exists. Under
GraalVM's strict missing-registration mode, a `Class.forName` of a name that is not registered throws an error rather
than reporting the class absent, and the probe does not catch it, under either name. Run a runtime mapper's image
without that mode, or register the names it can look up.

## A row typed by a class's type argument

A runtime `Mapping.to` or `Mapping.toOneWay` row can read a property declared with a type variable as a narrower class:
a row typed by `Tagged<String>` reads `Tagged<X>.value` as `String`, and so does a row reading a getter a generic
superclass declares through a subclass that fixes the variable. Nothing checked that class when the property was read,
so the mapper checks each value against it before the row's function runs, and refuses a value of another class by name.

The mapper finds that class in the method reference's `SerializedLambda`, which records it by name, and loads it with
`Class.forName` when the mapper is built. A class that is the property's own, or one of its supertypes, is recognised by
name and never loaded. In an image, `Class.forName` answers only for a class registered for reflection, so register the
class each such row reads its property as. The class alone is enough, with no members:

```json
{ "name": "com.example.Badge" }
```

Register a JDK class such as `java.lang.String` the same way. Whether an image can already look one up depends on the
metadata the other libraries in it contribute, which can change when a dependency does.

A class that is not registered fails the mapper's construction with an `IllegalStateException` naming the class and the
method reference, and under GraalVM's strict missing-registration mode the image's own missing-registration error passes
through instead. `NativeVerify` builds two such rows over the example's `Tagged` record, one reading it as `String` and
one as the example's `Badge` record, with both classes in the example's `reflect-config.json`.

## The order a bean's properties convert in

A runtime mapper converts a bean's properties in the order of the fields that back them, read with `getDeclaredFields`,
superclass fields first, and then the properties no field backs, by name. The generated bridge uses the same order. On
the JVM, `getDeclaredFields` returns the class file's order, which javac writes in source order. In an image, the fields
come from the reflection metadata registered for the bean, so their order is whatever the image builder recorded. The
`:core:imageTest` gate checks this order on the accessor substrate an image uses, but it runs on the JVM. `NativeVerify`
does not check the order, so a real image's order is not verified. It matters only to conversions with side effects.

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
3. **Generate from the model packages.** For a large plain-Java model, a small Gradle task can emit the `reflect-config`
   entries from the compiled classes directory. Walk **compiled classes, not sources** — nested types compile to their
   own `.class` files (`AccountBuilderBean$Builder.class`), so the classes walk catches them where a source walk
   silently misses them. Takes as many packages as you have model roots:

   ```kotlin
   tasks.register("generateReflectConfig") {
       dependsOn(tasks.compileJava)
       val pkgs = listOf("com.acme.app.model", "com.acme.billing.model")
       val classesRoot = layout.buildDirectory.dir("classes/java/main")
       val out = layout.buildDirectory.file("generated/native-config/reflect-config.json")
       inputs.dir(classesRoot)
       outputs.file(out)
       doLast {
           val root = classesRoot.get().asFile
           val entries = pkgs
               .flatMap { pkg ->
                   val base = root.resolve(pkg.replace('.', '/'))
                   base
                       .walkTopDown()
                       .filter { it.extension == "class" && it.name != "package-info.class" }
                       // Derive the binary name from the relative path so subpackages come out right:
                       // model/billing/Invoice.class → pkg.billing.Invoice, Foo$Builder.class → pkg.Foo$Builder.
                       .map { "$pkg." + it.relativeTo(base).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
                       .toList()
               }
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

   This exact task, pointed at the graphql example's model package, reproduces every entry of the example's hand-written
   config (nested `$Builder` included, correct subpackage FQNs) plus four extras — the codegen-generated classes that
   compile into the same package and need no registration. Which is the trade stated plainly: it over-registers (every
   type in the walked packages, all members), the price of not enumerating by hand. Wire the task before `nativeCompile`
   and keep the package boundaries tight. Note this produces `reflect-config.json` only: the `serialization-config`
   entries name the **call-site classes** holding your `.field(methodref)` references — usage information, not model
   information — so no walk of the model folder can derive them. Hand-list those (usually one to three classes), point
   the same walk at the package where your navigation code lives, or use `.fieldByName(String)` / codegen navigators and
   skip the file entirely.

4. **The GraalVM tracing agent.** Run the app or its tests once on the JVM with
   `-agentlib:native-image-agent=config-output-dir=...` and it dumps all the config files from observed behavior.
   Broadest coverage, noisiest output — entries for everything the run touched, not just your model — and it only
   records what actually executed, so an untested code path is an unregistered one.

## Verdict

**Runtime and codegen both work under GraalVM native-image.** Every verifier capability — record field update, record
read, bean read, the runtime record→record / record→bean / record→builder-bean mappers, `@FromMap`, a runtime `fromMap`
that fills a `@FromMap` component through its generated binder, `@Bridge`, a generated `@Focus` navigator, and a fused
`Telescope.all` over navigator paths alone — builds and runs in the native binary, confirmed by
`.github/workflows/native-image.yaml`. Telescope's runtime reflective mapper is AOT-capable out of the box for every
rebuild strategy (records, no-arg-ctor + setters, immutable `@Builder` targets); Wall B is fixed in `telescope-core`,
and the adopter supplies only the standard reachability metadata for their own types (`reflect-config.json` for
runtime-mapper DTOs, `serialization-config.json` for `.field(methodref)` call-site classes). Codegen nuance, verified by
the navigator capability: `@Bridge` and `@FromMap` need no config at all (they wrap concrete generated functions), while
a generated `@Focus`/`@BeanFocus` navigator composes its lenses from method references at call time — the generated
navigator class is itself a lambda-capturing type and goes in `serialization-config.json` alongside your own call-site
classes. A `Telescope.all` built only from navigator paths fuses inside an image without any reflection registration for
the navigated records: each record level whose edits all came from a navigator rebuilds through the generated setters.
The one runtime path still JVM/codegen-only under AOT is the Hibernate-proxy accessor. The workflow re-checks the whole
surface on every push to `main` and weekly against GraalVM updates, so a regression in any capability turns the job red.

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
