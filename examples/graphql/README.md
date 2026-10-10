# telescope-examples-graphql

The `graphql` example is a small [graphql-java](https://www.graphql-java.com/) server that turns the
`Map<String, Object>` argument of a GraphQL `createUser(input: {...})` mutation into a typed `User` record. It serves
over the JDK's built-in `com.sun.net.httpserver.HttpServer`. It shows two ways to do that conversion, and it also holds
the program that checks telescope inside a GraalVM native image (see
[`docs/native-image.md`](../../docs/native-image.md)).

## Entry points

| Entry point              | What it runs                                         | Gradle task                                             |
| ------------------------ | ---------------------------------------------------- | ------------------------------------------------------- |
| `RuntimeFromMapServer`   | the runtime `Telescope.fromMap(...)` converter       | `./gradlew :examples:graphql:runRuntimeFromMapServer`   |
| `GeneratedFromMapServer` | the `UserFromMap` class generated from `@FromMap`    | `./gradlew :examples:graphql:runGeneratedFromMapServer` |
| `NativeVerify`           | the native-image verifier, which is the image's main | `./gradlew :examples:graphql:runNativeVerify`           |

Each server starts on a free local port, sends one `createUser` mutation to itself, prints the response, and stops. The
two servers share the `GraphQlServer` class for the schema and the HTTP handling, and they differ only in the converter
the resolver calls. Both print the same response:

```
[runtime] {data={createUser={name=Alice, email=alice@example.com, age=30, role=ADMIN, address={city=New York, zip=10001}}}}
[generated] {data={createUser={name=Alice, email=alice@example.com, age=30, role=ADMIN, address={city=New York, zip=10001}}}}
```

`GeneratedFromMapServer` uses `UserFromMap` and `AddressFromMap`, which the `@FromMap` processor writes at compile time.
The generated code calls `new User(...)` directly, converts strings to `int` and to the `Role` enum inline, and calls
`AddressFromMap` for the nested address. It does not decode a `SerializedLambda`, call `LambdaMetafactory`, or use
reflection.

## The runtime converter in a native image

The runtime `Telescope.fromMap(...)` finds each field name from a method reference such as `User::name`. It reads the
name from a `SerializedLambda`, which it gets by calling the method reference's `writeReplace()` method. A native image
has that method only when the class that holds the method references is registered as a lambda-capturing type in a
`serialization-config.json`. The native-image guide calls this Wall A
([`docs/native-image.md`](../../docs/native-image.md#two-walls-and-how-each-falls)).

The example registers `NativeVerify` and the generated navigators. `NativeVerify` uses method references, so it runs the
same decode inside the image. `RuntimeFromMapServer` is left unregistered on purpose, so a native image built with it as
the main class shows the failure you get without the registration (abridged, with frames left out and the lambda's id
replaced by `<id>`):

```
Exception in thread "main" java.lang.IllegalArgumentException: Expected a method reference to a record component / bean property accessor
    at io.github.eschizoid.telescope.internal.LambdaIntrospection.decode
    at io.github.eschizoid.telescope.internal.LambdaIntrospection$MetadataSlot.get
    at io.github.eschizoid.telescope.internal.LambdaIntrospection.methodNameOf
    at io.github.eschizoid.telescope.FromMap.build
    at io.github.eschizoid.telescope.Telescope.fromMap
    at io.github.eschizoid.telescope.examples.graphql.server.RuntimeFromMapServer.converter
    ...
Caused by: java.lang.NoSuchMethodException: io.github.eschizoid.telescope.examples.graphql.server.RuntimeFromMapServer$$Lambda/0x<id>.writeReplace()
```

The generated converter does not decode method references, so it needs no registration. In short, `@FromMap` and
`@Bridge` code works in a native image with no reflection or serialization configuration. The runtime path works once
you add one `serialization-config.json` entry for each class that holds the method references. A generated `@Focus`
navigator builds its lenses from method references too, so each navigator class also needs an entry.

## Native build

The native build needs a GraalVM distribution that includes `native-image`. The build turns off toolchain detection, and
the project's normal compile toolchain is a plain JDK with no `native-image`. Point `GRAALVM_HOME` and `JAVA_HOME` at
the GraalVM distribution instead:

```bash
GRAALVM_HOME=/path/to/graalvm JAVA_HOME=/path/to/graalvm \
  ./gradlew :examples:graphql:nativeRun
```

The image's main class is `NativeVerify`. It runs each capability below and prints `PASS` or `FAIL` for each. It exits
with a non-zero status if any capability fails, so building and running the binary is the test.

- Record paths
  - a record field update and a record field read, through `Telescope.of(User.class).field(...)`
  - a bean getter read, through `Telescope.ofBean(AccountEntity.class).field(...)`
- Runtime mappers from `Telescope.mapper(...)`
  - record to record (`User` to `UserView`)
  - record to bean with a no-arg constructor and setters (`Account` to `AccountEntity`)
  - record to bean with only a builder (`Account` to `AccountBuilderBean`)
- Generated code
  - the generated `UserFromMap.fromMap(...)`
  - the generated `AccountBridge.BRIDGE.read(...)`
  - the generated `UserTelescope` navigator, read and update
- Other runtime paths
  - a runtime `Telescope.fromMap(...)` that fills `address`, which no row names, through the generated `AddressFromMap`
    it finds through its `ServiceLoader` registration
  - a `Telescope.all(over(...))` over `ShiftTelescope` navigator paths, on records (`Shift`, `Crewmate`) that have no
    reflection registration

The native build uses this configuration:

- `build.gradle.kts` passes `--no-fallback` and `--initialize-at-build-time` for the example's model package, which
  holds the generated `AccountBridge` and `UserFromMap` constants.
- `telescope-core` ships its own `native-image.properties`, which initializes the telescope packages at build time.
- `reflect-config.json` registers the model types that the runtime mappers read and build.
- `serialization-config.json` registers `NativeVerify` and the generated `UserTelescope`, `AddressTelescope`,
  `ShiftTelescope` and `CrewmateTelescope` navigators as lambda-capturing types.

The `Native Image Verification` workflow (`.github/workflows/native-image.yaml`) runs `nativeRun` on GraalVM for JDK 25.
It runs on manual dispatch, on pushes to `main` that change `examples/graphql`, `core`, `internal`, `codegen` or the
workflow file, and every Monday. It does not run on pull requests. The full contract is in
[`docs/native-image.md`](../../docs/native-image.md).

## Layout and tests

```
src/main/java/.../examples/graphql/
  model/    User, Address, Role        @FromMap and @Focus domain records, and the Role enum
            Account, AccountEntity     @Bridge pair (record to bean), also the runtime bean mapper target
            AccountBuilderBean         builder-only bean, the runtime builder mapper target
            UserView                   same-shape record, the runtime record mapper target
            Shift, Crewmate            @Focus records with no reflection registration, for Telescope.all
  server/   GraphQlServer              graphql-java and HttpServer harness (serveOnce)
            RuntimeFromMapServer       server using the runtime Telescope.fromMap converter
            GeneratedFromMapServer     server using the generated UserFromMap converter
            NativeVerify               the native-image verifier and the image's main class
src/main/resources/META-INF/native-image/io.github.eschizoid/telescope-examples-graphql/
  reflect-config.json                  model types for the runtime mappers
  serialization-config.json            NativeVerify and the generated navigators (Wall A)
src/test/java/.../examples/graphql/server/
  GraphQlServerTest                    checks both servers' responses through serveOnce
```

The servers only print their response. The checks live in `GraphQlServerTest` and in `NativeVerify`:

```bash
./gradlew :examples:graphql:test
./gradlew :examples:graphql:runNativeVerify   # checks the verifier on the JVM; nativeRun checks the native image
```
