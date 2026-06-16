# Telescope Feedback — Migration from MapStruct

Findings from migrating 12 MapStruct mappers to Telescope 1.0.0.

---

## Bugs

### 1. BridgeProcessor: ClassCastException when target class is not on processor classpath

**Severity:** P1 — crashes the build with an unhelpful stack trace.

**Reproduction:** Place `@Bridge(value = Foo.class)` on a class in module A, where `Foo` lives in module B and module A
does NOT depend on module B.

**Observed:**

```
java.lang.ClassCastException: class java.lang.String cannot be cast to class
javax.lang.model.type.TypeMirror
```

**Expected:** A clear compile error:

```
@Bridge target 'com.x.y.Foo' is not resolvable from this compilation unit — annotate the
other side, or add the dependency.
```

**Root cause:** When a `Class<?>` annotation value can't be resolved by the processor, `AnnotationValue.getValue()`
returns the FQN as a `String` instead of a `TypeMirror`. The processor doesn't guard against this case.

**File to fix:** `codegen/src/main/java/io/github/eschizoid/telescope/codegen/BridgeProcessor.java`

---

### 2. LambdaIntrospection NPE on `is*` boolean accessors

**Severity:** P0 — **BLOCKER**. Prevents using `Telescope.mapper()` / `mapperForward()` on any class with `boolean`
primitive fields.

**Reproduction:** Use any class with a `boolean` field as source or target in `Telescope.mapperForward()`:

```java
@Data
public class Order {
  private boolean shipped;   // Lombok generates isShipped()
  private String name;
}

@Data
public class OrderDto {
  private boolean shipped;
  private String name;
}

// This throws NPE at construction time — even if the boolean field isn't in any explicit to() row
Telescope.mapperForward(Order.class, OrderDto.class, writeBeans(SETTERS));
```

**Observed:**

```
NullPointerException: Cannot invoke "String.length()" because "getterName" is null
```

**Root cause (corrected after investigation):** The migration's "boolean accessor" attribution is a red herring —
`LambdaIntrospection.methodNameOf` cannot return null (it either returns the method name or throws). The actual trigger
is `DeepMap.populateIso` calling `srcRefl.normalize(row.sourceField())` unconditionally **before** the `instanceof` peel
that handles nested-telescope row shapes (`FromTelescopeTo`, `TelescopeToTelescope`) whose `sourceField()` returns
`null` by design. On a bean source side that `null` flows through `Beans.normalize` → `Beans.propertyOf(null)` and NPEs.

The fix is at the `DeepMap.populateIso` call site: peel the telescope sub-shapes before normalising, or guard the
normalize call with a null check. The defensive guard at `Beans.propertyOf` is kept as belt-and-suspenders.

**Workaround:** Add a null guard at the top of `Beans.propertyOf()`:

```java
public static String propertyOf(final String getterName) {
  if (getterName == null) return null;  // ← fix
  if (getterName.length() > 3 && getterName.startsWith("get")) ...
}
```

This allows the null to propagate gracefully (the null property is skipped by DeepMap).

**File to fix:** `internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java` — `propertyOf()` method

For explicit `to()` rows targeting boolean fields, use `fieldByName()`:

```java
to(Telescope.ofBean(Order.class).fieldByName("shipped"),
   Telescope.ofBean(OrderDto.class).fieldByName("shipped"))
```

---

### 3. DeepMap rejects primitive ↔ wrapper type pairs (no autoboxing)

**Severity:** P1 — prevents mapping any class with `boolean`/`Boolean` or `int`/`Integer` mismatches between source and
target.

**Reproduction:**

```java
@Data
public class Source {
  private boolean active;    // primitive
  private Integer count;     // boxed
}

@Data
public class Target {
  private Boolean active;    // boxed
  private int count;         // primitive
}

// Throws at construction time:
Telescope.mapperForward(Source.class, Target.class, writeBeans(SETTERS));
```

**Observed (shape mismatch):**

```
IllegalState: Deep map: component 'active' has incompatible source/target shapes — boolean vs
java.lang.Boolean. Shapes must match: same scalar, both records/beans, or both same-kind container.
```

**Observed (null unboxing at runtime when shapes happen to match directionally):**

```
NullPointerException: Cannot invoke "java.lang.Integer.intValue()" because the return value
of "Function.apply(Object)" is null
```

This occurs when a boxed `Integer` source value is `null` and gets auto-unboxed to primitive `int` on the target.

**Expected:** Automatic boxing/unboxing between primitive and wrapper types, matching the JavaBeans specification and
MapStruct behavior. `null` boxed values should map to the primitive's JLS default (`0`, `false`, `'\0'`).

**Workaround:** Add explicit `forward()` rows with manual boxing:

```java
forward(Source::getCount, Target::getCount, i -> i != null ? (int) i : 0),
forward(Telescope.ofBean(Source.class).fieldByName("active"),
        Target::getActive, b -> (Boolean) b)
```

**Proposed fix:** DeepMap's shape-compatibility check should treat primitive/wrapper pairs as compatible and
automatically insert boxing/unboxing with null-safe defaults.

**File to fix:** `core/src/main/java/io/github/eschizoid/telescope/DeepMap.java` — `computeAutoIso()` method, add
`isPrimitiveWrapperPair` check between steps (a) and (b)

---

### 4. NPE on null intermediate objects in nested telescope paths

**Severity:** P1 — crashes at runtime when any intermediate hop in a nested path is null.

**Reproduction:**

```java
@Data
public class Order {
  private Customer customer;   // may be null
}

@Data
public class Customer {
  private String email;
}

@Data
public class OrderDto {
  private String customerEmail;
}

// Mapper with nested source path:
Telescope.mapperForward(Order.class, OrderDto.class,
  to(Telescope.ofBean(Order.class).field(Order::getCustomer).field(Customer::getEmail),
     OrderDto::getCustomerEmail),
  writeBeans(SETTERS));

// When order.customer is null:
mapper.forward(order);   // NPE!
```

**Observed:**

```
NullPointerException: Cannot read field "classValueMap" because "type" is null
   at java.lang.ClassValue.get(ClassValue.java:103)
   at io.github.eschizoid.telescope.internal.Beans.readProperty(Beans.java:375)
```

**Root cause:** When a multi-hop telescope path reads through a null intermediate (e.g., `order.getCustomer()` returns
null), the next lens hop calls `Beans.readProperty(null, "email")`. `persistentClassOf(null)` returns null, then
`ClassValue.get(null)` throws NPE.

**Expected:** Short-circuit to null — same as MapStruct which generates:

```java
if (source.getCustomer() != null) {
  target.setCustomerEmail(source.getCustomer().getEmail());
}
```

**Workaround:** Add a null guard at the top of `Beans.readProperty()`:

```java
public static Object readProperty(final Object pojo, final String name) {
  if (pojo == null) return null;   // ← fix: short-circuit on null intermediate
  ...
}
```

**Proposed fix:** `Beans.readProperty` should return null when `pojo` is null, allowing the optic pipeline to propagate
null gracefully through intermediate hops.

**File to fix:** `internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java` — `readProperty()` method
(line ~371)

---

### 5. SettersWriter throws on getter-only (read-only) properties

**Severity:** P1 — prevents using `writeBeans(SETTERS)` on any class with getter-only methods.

**Reproduction:**

```java
public class OrderResponse {
  private boolean processed;

  // Getter-only — no setProcessed() exists
  public boolean isProcessed() {
    return processed;
  }
}

// Throws at mapper construction time:
Telescope.mapperForward(Order.class, OrderResponse.class, writeBeans(SETTERS));
```

**Observed:**

```
IllegalArgumentException: writeBean(OrderResponse, SETTERS): no setter 'setProcessed'
```

**Root cause:** `SettersWriter.buildSetterInvoker()` scans `getMethods()` for a matching `setX()`. When no setter exists
(getter-only property — common for derived/computed fields), it throws instead of skipping the property.

**Expected:** Silently skip getter-only properties during construction — MapStruct ignores them by default. A property
with a getter but no setter is read-only and shouldn't be written during object construction.

**Workaround:** Replace the throw with a no-op consumer:

```java
if (setter == null) return (obj, val) -> {};  // read-only property, skip
```

**Proposed fix:** `SettersWriter.buildSetterInvoker()` should return a no-op `BiConsumer` for properties without
setters, matching MapStruct's behavior of silently ignoring unmapped/unwritable target fields.

**File to fix:** `internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java` —
`SettersWriter.buildSetterInvoker()` method (line ~1466)

---

### 6. DeepMap strict bijection enforced on nested auto-recursed types

**Severity:** P1 — prevents mapping any pair where nested sub-objects have asymmetric field sets.

**Reproduction:**

```java
@Data
public class ScoreResponse {
  private int firstNameScore;
  private int lastNameScore;
}

@Data
public class ScoreResponseDto {
  private int firstNameScore;
  private int lastNameScore;
  private String matchingStatus;   // extra field, not on source
}

@Data
public class Parent {
  private ScoreResponse scores;
}

@Data
public class ParentDto {
  private ScoreResponseDto scores;
}

// Throws during auto-recursion of the nested pair:
Telescope.mapperForward(Parent.class, ParentDto.class, writeBeans(SETTERS));
```

**Observed:**

```
IllegalState: Deep map ScoreResponse → ScoreResponseDto: target property 'matchingStatus'
has no same-name source property.
```

**Root cause:** `DeepMap.populateIso()` enforces strict bijection on EVERY type pair it encounters during recursive
descent — including nested types that the user never explicitly configured. When a nested target type has fields the
nested source doesn't, the entire top-level mapper construction fails.

**Expected:** Nested auto-recursed pairs should be lenient — unmatched target fields get JLS defaults (null/0/false),
unmatched source fields are silently dropped. Only the top-level pair (or explicitly `@Bridge`-annotated pairs) should
optionally enforce strictness.

**Workaround:** Remove both the source-side and target-side `throw` in `DeepMap.populateIso()`, replacing with
placeholder Isos:

```java
// Target side (line ~467): replace throw with:
final var fieldType = rawClassOf(tgtRefl.genericType(target, name));
byTargetName.putIfAbsent(name, new FieldStep(null, name, placeholderIsoFor(fieldType, false)));
continue;

// Source side (line ~491): replace throw with:
bySourceName.putIfAbsent(name, new FieldStep(name, null, NULLING_ISO));
continue;
```

**File to fix:** `core/src/main/java/io/github/eschizoid/telescope/DeepMap.java` — `populateIso()` method, both
source-side and target-side unmatched property checks

---

### 7. LambdaMetafactory fails on classes extending JDK collection types

**Severity:** P1 — prevents mapping any bean graph containing a class that extends `ArrayList`, `HashMap`, etc.

**Reproduction:**

```java
public class ImageUrls extends ArrayList<ImageUrl> {
  // custom collection wrapper — common in legacy codebases
}

@Data
public class DocumentData {
  private ImageUrls imageUrls;
}

// When Telescope auto-recurses into DocumentData and encounters ImageUrls:
Telescope.mapperForward(Source.class, Target.class, writeBeans(SETTERS));
```

**Observed:**

```
IllegalStateException: Failed to build LambdaMetafactory getter invoker for ImageUrls.empty
Caused by: LambdaConversionException: Invalid caller: java.util.ArrayList
```

**Root cause:** `Beans.scanGetters()` discovers inherited methods from `java.util.ArrayList` (like `isEmpty()` which
maps to property `empty`). Then `buildGetterInvokers()` calls `MethodHandles.privateLookupIn(ArrayList.class)` — which
the JVM rejects because `java.util.ArrayList` is in `java.base` module and can't be accessed via private lookup from
application code.

**Expected:** Skip inherited methods from `java.base` module classes during property discovery. A class extending
`ArrayList` should only expose its **own declared** getters as bean properties, not inherited JDK methods.

**Proposed fix:** Two changes needed:

1. In `Beans.scanGetters()`, skip methods declared in platform modules:

```java
final var declaringModule = m.getDeclaringClass().getModule();
if (declaringModule != null && declaringModule.isNamed() &&
    declaringModule.getName().startsWith("java.")) continue;
```

2. In `DeepMap.isReflectable()`, treat Collection/Map subtypes as scalars (pass-through by reference, not
   bean-decomposed):

```java
if (java.util.Collection.class.isAssignableFrom(cls)) return false;
if (java.util.Map.class.isAssignableFrom(cls)) return false;
```

**Files to fix:**

- `internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java` — `scanGetters()` method (line ~577)
- `core/src/main/java/io/github/eschizoid/telescope/DeepMap.java` — `isReflectable()` method (line ~977)

---

### 8. SettersWriter NPE when valueByName returns null for a property without a setter

**Severity:** P1 — NPE at runtime during object construction when a getter-only property has a null value.

**Reproduction:**

```java
@Data
public class GovtIdDBData {

  private Integer docUpldAtmptCnt; // has getter and setter
  // inherited isEiPublisherInvoked() — getter only, no setter
}

// After Bug 5 fix (no-op for missing setter), the no-op consumer still
// receives the value from valueByName — but if the property name
// resolves to null in the source-side reading, the no-op fires
// with a null that propagates to other setters in the chain.
```

**Observed:**

```
NullPointerException at Beans$SettersWriter.construct(Beans.java:1445)
```

**Root cause:** When the lenient bijection allows unmatched target properties (Bug 6 fix), the `valueByName` function
returns `null` for those properties. The `SettersWriter.construct()` iterates ALL property names and calls
`setterFor(name).accept(pojo, valueByName.apply(name))`. For primitive setters like `setDocUpldAtmptCnt(int)`, passing
`null` causes unboxing NPE.

**Expected:** `SettersWriter` should null-guard values for primitive setters — if `valueByName` returns null for a
primitive property, use the JLS default (0, false, etc.) instead of passing null.

**Proposed fix:** In `SettersWriter.construct()`:

```java
for (final var name : names) {
  final var value = valueByName.apply(name);
  if (value == null && isPrimitiveSetter(name)) continue;  // skip null → primitive
  setterFor(name).accept(pojo, value);
}
```

Or wrap the `BiConsumer` to null-guard at the individual setter level.

**File to fix:** `internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java` —
`SettersWriter.construct()` method (line ~1440)

---

## Enhancement Requests

### 1. Cross-module `@Bridge` support (MapStruct parity)

**Problem:** `@Bridge` must live on the model class, which constrains it to types visible from that class's own Maven
module. When source and target live in different modules without a dependency path, the annotation can't be placed on
either side.

**How MapStruct solves it:** The `@Mapper` interface is a standalone file in the consuming module (which depends on all
needed modules). The model classes stay annotation-free.

**Proposed solution:** Allow `@Bridge` on a "carrier" class that declares both source and target explicitly:

```java
@Bridge(
  source = IdentityDocumentDBDetails.class,
  target = IdentityDocumentDetailsBO.class,
  renames = { @Rename(source = "icVerificationExt", target = "vendorExtendedResult") }
)
public class IdentificationBridgeDef {}
```

This carrier lives in a module that sees both types. The processor emits `IdentificationBridgeDefBridge.BRIDGE` as the
constant.

---

### 2. `ForwardMapper.liftList()` / `liftSet()` / `liftOptional()`

**Problem:** `liftList()` only exists on `Mapper<A,B>`. When a mapper is genuinely forward-only (`ForwardMapper<A,B>`),
you can't lift it to a list without wrapping it back into a `Mapper`:

```java
// Works
Mapper<A, B> m = Telescope.mapper(...);
Mapper<List<A>, List<B>> listMapper = m.liftList();

// Doesn't work
ForwardMapper<A, B> fm = Telescope.mapperForward(...);
fm.liftList();   // ← method doesn't exist
```

**Workaround:** Keep those specific beans as `Mapper` even though backward is never called.

**Proposed:** Add `ForwardMapper.liftList()`, `liftSet()`, `liftOptional()` returning `ForwardMapper<List<A>, List<B>>`
etc.

---

### 3. `Telescope.asForwardMapper()` / `Mapper.toForwardMapper()`

**Problem:** `@Bridge` generates a `Telescope<A,B>` constant. To expose it as a `ForwardMapper<A,B>` bean, you need:

```java
ForwardMapper.create(SomeBridge.BRIDGE::read, A.class, B.class);
```

This is verbose and requires repeating the type classes.

**Proposed:** Add a convenience method:

```java
ForwardMapper<A, B> fm = SomeBridge.BRIDGE.asForwardMapper();

// or
ForwardMapper<A, B> fm = existingMapper.toForwardMapper();
```

---

### 4. Annotation processor ordering documentation

**Problem:** When Lombok and telescope-lombok are both on the annotation processor path, processor ordering matters.
`LombokFocusProcessor` correctly defers via `processingOver()`, but `BridgeProcessor` does NOT defer — it processes
immediately and may see "known fields: []" on Lombok-generated targets.

**Current fix:** Explicit `annotationProcessorPaths` in Maven with Lombok listed first:

```xml
<annotationProcessorPaths>
  <path>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.30</version>
  </path>
  <path>
    <groupId>io.github.eschizoid</groupId>
    <artifactId>telescope-lombok</artifactId>
    <version>1.0.0</version>
  </path>
</annotationProcessorPaths>
```

**Proposed:**

1. Document this requirement in the README / Getting Started guide.
2. Make `BridgeProcessor` also defer emission to `processingOver()` when it detects Lombok annotations on the source or
   target (same strategy as `LombokFocusProcessor`).

---

### 5. Untyped source mapping (`Map<String, Object>` → POJO)

**Problem:** No first-class support for mapping from untyped sources (raw Maps, JSON nodes). Common in legacy code that
receives `Map<String, Object>` from frameworks.

**Current workaround:** Target-side-only `Telescope.all(Edit.over(...))` pattern — still imperative on the extraction
side.

**Proposed:** A `Telescope.fromMap(Class<T> target, MapExtractStep...)` factory:

```java
ForwardMapper<Map<String, Object>, CaseListRequest> m = Telescope.fromMap(
  CaseListRequest.class,
  extract("bookingType", CaseListRequest::getBookingType, Extractors::firstStringOrValue),
  extract("caseId", CaseListRequest::getCaseId, Object::toString)
);
```

---

### 6. `@Bridge` lenient mode — skip unmatched fields

**Problem:** `@Bridge` enforces strict bijection. If the target has 130 fields that the source doesn't provide, you'd
need 130 `@Constant(field = "x", value = "null")` entries — completely impractical.

**Real-world pattern:** `CustomerCaseRequest` (7 fields) → `GovtIdDBData` (135 fields). Only 6 fields actually map; the
other 129 should stay at JLS defaults.

**Proposed:** A `lenient = true` flag on `@Bridge`:

```java
@Bridge(value = GovtIdDBData.class, lenient = true,
        renames = {@Rename(source = "referenceID", target = "entRefncId"), ...})
```

When `lenient = true`:

- Unmatched source fields are silently dropped (no `drops` required)
- Unmatched target fields receive JLS defaults (no `constants` required)
- Only explicitly declared renames/transforms are enforced

This makes `@Bridge` practical for the common "small DTO → large entity" pattern.

---

### 7. `Sources.byClass()` type safety

**Problem:** `Sources.byClass(Class<T>)` returns `Object` requiring a cast:

```java
final var headers = (PolicyRequestHeaders) sources.byClass(PolicyRequestHeaders.class);
```

**Proposed:** Return `T` directly with generic signature:

```java
public <T> T byClass(Class<T> type) { ... }
```

---

### 8. `Mapping.forward()` naming

**Problem:** `Mapping.forward(src, tgt, fn)` is the forward-only row factory. But when static-imported alongside
`Mapping.to()`, the name `forward` conflicts with common variable/method names and is less expressive than `to()`.

**Consider:** `Mapping.toOneWay(src, tgt, fn)` or `Mapping.map(src, tgt, fn)` — makes the unidirectionality visible at
the call site without requiring readers to check the import.

---

### 9. `mapperForward()` should be lenient by default

**Problem:** `Telescope.mapperForward()` runs the same strict bijection check as `Telescope.mapper()` — it throws at
construction time if the target has properties with no same-name source property:

```
IllegalState: Deep map DocumentT → IdentificationResponse: target property 'documentStatus'
has no same-name source property. Add a rename row to(sourceAccessor, targetAccessor)
that maps to 'documentStatus'.
```

This forces the caller to declare `constant(Target::getUnmatchedField, null)` for every unmatched target property and
`drop(Source::getUnmatchedField)` for every unmatched source property — defeating the purpose of forward-only semantics.

**Why it's wrong for forward-only:** A `ForwardMapper<A, B>` is explicitly one-directional. There's no `backward()`
call, so the bijection invariant (round-trip losslessness) doesn't apply. Unmatched target fields should silently
receive JLS defaults, and unmatched source fields should be silently ignored — that's what MapStruct does for every
mapper.

**Real-world impact:** A simple 3-field rename between `DocumentT` (39 fields) → `IdentificationResponse` (29 fields)
required 13 `drop()` + 2 `constant()` rows just to satisfy the strict check — turning a 3-line mapper into a 20-line
mapper.

**Proposed fix:** `mapperForward()` should NOT enforce the strict bijection check. Unmatched source properties are
ignored; unmatched target properties stay at their default values. Only `mapper()` (bidirectional) should enforce
strictness.

**Workaround:** Use `ForwardMapper.create(manualMappingFunction, Source.class, Target.class)` to bypass DeepMap
entirely.

**File to fix:** `core/src/main/java/io/github/eschizoid/telescope/DeepMap.java` — same `populateIso()` fix as Bug 6
covers this for `mapperForward()` too

---

## Additional Fixes Applied During Migration (not listed as separate bugs)

| Fix                           | File                                     | Description                                                                                                   |
| ----------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `forward(null)` returns null  | `core/.../conversion/ForwardMapper.java` | Null input should return null, not NPE                                                                        |
| `forward(null)` returns null  | `core/.../conversion/Mapper.java`        | Same                                                                                                          |
| `fieldByName()` bean dispatch | `core/.../Telescope.java`                | `fieldByName()` used `Records.fieldLens()` unconditionally — should dispatch to `Beans.fieldLens()` for POJOs |
| `Telescope.read()` null-safe  | `core/.../Telescope.java`                | `optic.getAll(source).findFirst()` NPEs on null elements in stream — use `toList().get(0)`                    |
| `Beans.fieldLens(String)`     | `internal/.../Beans.java`                | New deferred bean lens for `fieldByName()` on POJOs (didn't exist)                                            |

---

## Summary

| #     | Type                                                            | Impact | Status         |
| ----- | --------------------------------------------------------------- | ------ | -------------- |
| Bug 1 | ClassCastException on unresolvable `@Bridge` target             | P1     | Fixed (v1.0.1) |
| Bug 2 | LambdaIntrospection NPE on `is*` boolean accessors              | P0     | Fixed (v1.0.1) |
| Bug 3 | No autoboxing between primitive ↔ wrapper types                 | P1     | Fixed (v1.0.1) |
| Bug 4 | NPE on null intermediate objects in nested paths                | P1     | Fixed (v1.0.1) |
| Bug 5 | SettersWriter throws on getter-only properties                  | P1     | Fixed (v1.0.1) |
| Bug 6 | DeepMap strict bijection enforced on nested auto-recursed types | P1     | Fixed (v1.0.1) |
| Bug 7 | LambdaMetafactory fails on classes extending JDK types          | P1     | Fixed (v1.0.1) |
| Bug 8 | SettersWriter NPE when valueByName returns null for primitive   | P1     | Fixed (v1.0.1) |
| 1     | Cross-module `@Bridge` carrier                                  | High   | Enhancement    |
| 2     | `ForwardMapper.liftList()`                                      | Medium | Enhancement    |
| 3     | `Telescope.asForwardMapper()`                                   | Low    | Convenience    |
| 4     | Processor ordering docs + BridgeProcessor deferral              | Medium | Docs + Fix     |
| 5     | `Map` → POJO factory                                            | Low    | Nice-to-have   |
| 6     | `@Bridge` lenient mode                                          | High   | Enhancement    |
| 7     | `Sources.byClass()` generics                                    | Low    | API polish     |
| 8     | `Mapping.forward()` naming                                      | Low    | Bikeshed       |
| 9     | `mapperForward()` lenient by default                            | High   | Enhancement    |

---

## Known limitation surfaced during round 6 review (post-fix)

**Parameterised Collection / Map subtype pairs across DIFFERENT raw classes** still throw "incompatible source/target
shapes" at mapper construction.

Example: `Outer.items : List<Inner>` (parameterised source) ↔ `OuterDto.items : ArrayList<InnerDto>` (parameterised
target with different concrete class). The Bug 7 fix handles the _raw-subtype-on-both-sides_ case
(`ImageUrls extends ArrayList<X>` on both sides). It does not yet cover the case where one side uses an interface
(`List<X>`) and the other a concrete implementation (`ArrayList<Y>`) or two different concrete implementations
parameterised over different element types.

Workaround for v1.0.1: use the same raw container type on both sides, or declare an explicit `Mapping.via(...)` row.

v1.1 work: extend `ContainerShape.of` to accept parameterised types whose raw class is a SUBTYPE (not exact match) of
`List` / `Set` / `Map` / `Optional`, and combine with the per-element Iso lift + target-concrete-class allocator so the
lifted Iso writes into a fresh instance of the target's concrete class.
