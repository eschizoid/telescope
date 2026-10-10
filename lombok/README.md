# telescope-lombok

`telescope-lombok` is an annotation processor that generates a typed `<X>Telescope<R>` navigator for each class that
carries Lombok's `@Data`, `@Value` or `@Builder`. The navigator has the same shape that
[`telescope-codegen`](../codegen/README.md) generates for `@Focus` records and `@BeanFocus` classes, and it reads and
rebuilds the class through the getters, setters, constructor or builder that Lombok adds.

You don't add any telescope annotation to your classes. The processor finds the Lombok annotations by name, so the
processor jar has no compile-time dependency on Lombok.

## When to use

Use this module when your model classes are Lombok beans and you want a compile-checked navigator for them. For example,
take two `@Data` classes:

```java
@Data
public class User {

  private String id;
  private String email;
  private Address address;
}

@Data
public class Address {

  private String street;
  private String city;
}
```

The processor generates `UserTelescope` and `AddressTelescope`. Because `Address` has its own navigator, the `address()`
step of `UserTelescope` continues into it:

```java
User updated = UserTelescope.of().address().city().update(user, String::toLowerCase);
```

The update returns a new `User` with a new `Address`, and the original `user` is left unchanged.

## Install

You need Java 21 or later. Put Lombok and `telescope-lombok` on the annotation processor path, and put `telescope-core`
on the compile classpath, because the generated code calls it.

`telescope-lombok` depends on `telescope-codegen` and `telescope-core`, so both come onto the processor path with it.
The `telescope-codegen` processors for `@Focus`, `@BeanFocus`, `@Bridge` and the rest therefore run as well, and you
don't need to add `telescope-codegen` separately.

With Gradle:

```kotlin
dependencies {
    implementation("io.github.eschizoid:telescope-core:2.0.0")

    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")
    annotationProcessor("io.github.eschizoid:telescope-lombok:2.0.0")
}
```

With Maven:

```xml
<dependencies>
  <dependency>
    <groupId>io.github.eschizoid</groupId>
    <artifactId>telescope-core</artifactId>
    <version>2.0.0</version>
  </dependency>
  <dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.48</version>
    <scope>provided</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>1.18.48</version>
          </path>
          <path>
            <groupId>io.github.eschizoid</groupId>
            <artifactId>telescope-lombok</artifactId>
            <version>2.0.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

The order of Lombok and `telescope-lombok` on the processor path doesn't matter, for the reason given in the next
section.

## When the navigator is generated

Lombok adds its getters, setters, constructors and builder by changing the class's syntax tree during compilation.
Depending on where Lombok sits on the processor path, another processor that reads the class in the first processing
round can see it before Lombok has added those members.

`telescope-lombok` handles the timing in the following way:

1. First, it records every class with `@Data`, `@Value` or `@Builder` in the round where the class appears.
2. Second, in each round it checks every recorded class. A class is ready when it has readable properties and every
   member its Lombok annotations add is visible, e.g. the static `builder()` of `@Builder` and the setters of `@Data`.
3. Third, it generates the navigator for each ready class in that same round, and checks the rest again in the next
   round.
4. Last, in the final round it runs every class that is still not ready through the generator anyway. A class with no
   readable properties then fails the build with a "has no readable properties (getX()/isX())" error, rather than being
   skipped without a message.

Because a navigator is generated as soon as its class is ready, main code in the same module can import and call it
directly. The `LombokDemo` class in `examples/library` does that, and it fails to compile if the navigator is generated
too late.

## Generated output

For each class the processor generates two classes in the same package:

- `<X>Telescope<R>`, the navigator, with the same shape as the one `telescope-codegen` generates. See
  [Navigators](../codegen/README.md#navigators-focus-and-beanfocus) in that module's README.
- `<X>FieldOptics`, a holder of one prebuilt path per property. When the holder is present, a runtime call such as
  `Telescope.ofBean(User.class).field(User::getEmail)` uses its constant instead of building the path by reflection.

Unlike `@Focus` and `@BeanFocus`, which accept only top-level types, `telescope-lombok` also handles a nested static
class. Its generated classes get names that include the enclosing class. For example, `Outer.Inner` produces
`OuterInnerTelescope` and `OuterInnerFieldOptics`.

The navigator reads each property through its getter, so the class needs getters. `@Data` and `@Value` add them, but
`@Builder` does not, so pair `@Builder` with `@Getter`. To write a property, the navigator builds a new object. It takes
the first of these ways to build the class that the class offers, in the same order the runtime writer uses:

1. A static `builder()` whose builder has a `build()` method, as `@Builder` adds.
2. A public constructor whose parameters are named after every property, as `@Value` or `@AllArgsConstructor` adds.
3. A no-argument constructor followed by one setter call per property, as `@Data` provides when the class has no final
   fields.

A builder is passed over when it lacks a method for a property that the next way would write, because the builder would
drop that value. For example, a plain `@Data` class is rebuilt through its setters, a `@Value` class through its
constructor, and a `@Builder @Getter` class through its builder.

## Multiple edits

`Telescope.all(...)` fuses edits made through a generated navigator into one pass over the object, the same way it fuses
edits on hand-written `Telescope.ofBean(...)` paths. Navigator paths and hand-written paths through the same properties
also fuse with each other:

```java
import static io.github.eschizoid.telescope.Edit.over;

var normalize = Telescope.all(
  over(UserTelescope.of().email(), String::toLowerCase),
  over(UserTelescope.of().address().city(), String::toLowerCase)
);

User clean = normalize.apply(user);
```

## Using @BeanFocus on a Lombok class

You don't need `@BeanFocus` on a Lombok class, but it is allowed. When `telescope-lombok` is on the processor path, it
generates the navigator for the class and the `@BeanFocus` processor skips the class. The result is the same as without
`@BeanFocus`.

Without `telescope-lombok`, the `@BeanFocus` processor waits until the final processing round to generate the navigator
for a Lombok class. Main code in the same module then can't refer to that navigator.

## Choosing a module

| Your classes                                                | Module                                                        |
| ----------------------------------------------------------- | ------------------------------------------------------------- |
| Records                                                     | [`telescope-codegen`](../codegen/README.md) with `@Focus`     |
| Classes with hand-written getters, setters or a builder     | [`telescope-codegen`](../codegen/README.md) with `@BeanFocus` |
| Classes with Lombok's `@Data`, `@Value` or `@Builder` added | `telescope-lombok`, with no telescope annotation              |
