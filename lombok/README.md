# telescope-lombok

Compile-time `<X>Path<R>` navigator emission for Lombok-annotated POJOs. Sibling of
[`telescope-codegen`](../codegen/README.md), out-of-tree consumer of the same `AbstractTelescopeProcessor` base — split
because Lombok's lazy AST patching breaks the in-memory test harness `:codegen` uses, and round-deferred emission means
a different processor lifecycle.

If you write `@Data` / `@Value` / `@Builder` POJOs and want the same reflection-free navigator shape that `@Focus` gives
records, this is the processor.

## When to use

You have a Lombok POJO:

```java
@Data
public class User {

  private String id;
  private String email;
  private Address address;
}
```

You want the same compile-checked navigator as `@Focus`/`@BeanFocus`:

```java
UserPath.of()
    .address().city()
    .update(user, city -> city.toLowerCase());
```

Add the `telescope-lombok` annotation processor alongside Lombok itself. No annotations on your classes needed — the
processor detects `@lombok.Data`, `@lombok.Value`, and `@lombok.Builder` by string FQN and emits the navigator. (No
compile-time Lombok dependency on the processor jar — the detection is graceful no-op if Lombok isn't on the consumer's
classpath.)

## Install

```kotlin
// Gradle
dependencies {
    implementation("io.github.eschizoid:telescope-core:0.13.0")

    // Lombok itself
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    // Telescope's Lombok-aware codegen — must come AFTER Lombok in the processor list so
    // Lombok's AST patches have fired when telescope reads the synthesized members.
    annotationProcessor("io.github.eschizoid:telescope-lombok:0.13.0")
}
```

```xml
<!-- Maven -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.42</version>
      </path>
      <path>
        <groupId>io.github.eschizoid</groupId>
        <artifactId>telescope-lombok</artifactId>
        <version>0.13.0</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

## The round-deferred-emission gotcha

Don't process a Lombok-annotated class in round 1 of annotation processing. Lombok installs lazy AST visitors during
processor init that patch class declarations on traversal. Those visitors haven't necessarily fired by round 1; a
processor that queries `Elements.getAllMembers()` on a `@Data` class in round 1 sees the un-patched member list (no
synthesised getters / setters / builder), causing a "no readable properties" error.

`telescope-lombok` handles this by collecting Lombok-annotated targets every round and only emitting on
`processingOver()`. Any future Lombok-touching processor in this repo must follow the same pattern — see
`LombokFocusProcessor.java`.

## Generated output

Same `<X>Path<R>` shape as [`telescope-codegen`'s](../codegen/README.md) emission — see that module's README for the
full structure. The processor uses the Lombok-synthesised getter / setter / builder where present:

- `@Data` / `@Value` → read via `getFoo()` / `isFoo()`, rebuild via no-arg ctor + setters
- `@Builder` → rebuild via the generated `Builder.foo(value).build()` chain

If a class carries both `@Data` and `@Builder`, the builder rebuild path wins (higher fidelity for field-renamed builder
methods).

## Caveat: same-module main code can't reference the emitted Path

Lombok-emitted `<Pojo>Path<R>` is emitted in `processingOver()` — the final processor round, after main-source symbol
resolution. Same-module main code that directly references `<Pojo>Path` won't compile, since the symbol isn't visible
until after main resolution finishes.

Workarounds:

- Reference the Path from test code (works — test compilation is a separate phase)
- Reference from downstream modules (works — they're compiled later, the jar carries the emitted Path)
- For same-module main code, use the reflective `Telescope.ofBean(Pojo.class).field(Pojo::getFoo)` path instead.
  Slightly slower; gets the same end-state.

This is a structural limitation of Lombok's lifecycle, not a `telescope-lombok` bug.

## Cross-module symmetry

| Use case                                                 | Module                                                        |
| -------------------------------------------------------- | ------------------------------------------------------------- |
| Records (`record User(String id, String email) {}`)      | [`telescope-codegen`](../codegen/README.md) with `@Focus`     |
| Plain POJOs (manual getters / setters / builder)         | [`telescope-codegen`](../codegen/README.md) with `@BeanFocus` |
| Lombok-generated POJOs (`@Data` / `@Value` / `@Builder`) | **`telescope-lombok`** (no annotation needed)                 |
