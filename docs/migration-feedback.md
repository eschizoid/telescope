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

**Workaround:** Add explicit `Mapping.toOneWay()` rows with manual boxing:

```java
toOneWay(Source::getCount, Target::getCount, i -> i != null ? (int) i : 0),
toOneWay(Telescope.ofBean(Source.class).fieldByName("active"),
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

### 9. `forward(null)` / `backward(null)` NPEs instead of returning null

**Severity:** P1 — every adopter wiring a nullable upstream (REST controller pulling a possibly-null JSON body,
Hibernate query returning `null` for a missing row, etc.) hits this on day one.

**Reproduction:**

```java
final var mapper = Telescope.mapper(Order.class, OrderDto.class);

final OrderDto dto = mapper.forward(null); // NPE in iso.to(null)
```

`Mapper.forward(A)` and `ForwardMapper.forward(A)` both call into `iso.to(...)` / `forward.get(...)` without
short-circuiting on a null input. MapStruct's generated mappers always emit a top-of-method
`if (source == null) return null;` — adopters expect the same.

**Expected:** Null in, null out. Matches MapStruct's `@Mapping` default and the JPA "no row" idiom.

**File to fix:** `core/src/main/java/io/github/eschizoid/telescope/conversion/Mapper.java` (forward + backward) and
`core/src/main/java/io/github/eschizoid/telescope/conversion/ForwardMapper.java` (forward).

---

### 10. `Telescope.fieldByName(String)` uses `Records.fieldLens()` unconditionally — broken for POJOs

**Severity:** P1 — `fieldByName` is the documented runtime escape hatch (the README's "config-driven field paths"
example). It's also the only string-keyed nav method on the public surface. Today it works only on records.

**Reproduction:**

```java
class Order { private String id; public String getId() { return id; } public void setId(String id) { this.id = id; } }
final var path = Telescope.ofBean(Order.class).<String>fieldByName("id");
path.read(new Order()); // RuntimeException — Records.fieldLens probes a record class
```

**Root cause:** `Telescope.fieldByName(String)` builds a `Records.fieldLens(fieldName)` Lens regardless of whether the
calling Telescope was constructed via `of(...)` (record) or `ofBean(...)` (POJO). The Telescope already carries a
`fieldOptics` discriminator (`RecordFieldOptics.INSTANCE` vs `BeanFieldOptics.INSTANCE`) used by `.field(Accessor)` —
`fieldByName(String)` needs the same dispatch.

**Expected:** `fieldByName(String)` on a bean Telescope routes through a sibling `Beans.fieldLens(String)` that uses
`readProperty(pojo, name)` for `get` and `autoWriter(pojo.getClass()).construct(...)` for `set` / `modify`. Deferred-
class lookup mirrors the existing `Records.fieldLens(String)` design (the runtime class is probed at call time, not
construction time).

**File to fix:**

- `internal/src/main/java/io/github/eschizoid/telescope/internal/Beans.java` — add a new `Beans.fieldLens(String)`
  method modelled on `Records.fieldLens(String)`.
- `core/src/main/java/io/github/eschizoid/telescope/Telescope.java` — dispatch in `fieldByName(String)` on the carried
  `fieldOptics`.

**Known caveat (lens-law gap for primitive properties):** the new `Beans.fieldLens(String)` writes through
`autoWriter(source.getClass())` which routes through `SettersWriter`. `SettersWriter`'s primitive setter is null-guarded
(Bug 8): if you call `lens.set(bean, null)` on a property whose setter takes a Java primitive (e.g. `int count`), the
setter call is skipped and the field stays at the JLS default. Reading back yields `0` (auto-boxed), not `null`. This
means the lens-law `get(set(s, null)) == null` does NOT hold for primitive properties.

```java
class Order { int count; public int getCount() { return count; } public void setCount(int c) { this.count = c; } }
final var lens = Telescope.ofBean(Order.class).<Integer>fieldByName("count");
final var order = new Order(); order.setCount(42);
final var result = lens.update(order, c -> null);  // set(s, null) via the modify path
lens.read(result);  // → 0, not null
```

The cure (drop the SettersWriter null-guard so the primitive setter NPEs on null) is worse than the gap, because it
reintroduces Bug 8 and breaks the MapStruct-parity contract (`@Mapping` default is null → JLS-default substitution for
primitive targets). **Workaround:** declare the property as a boxed wrapper (`Integer count` instead of `int count`) if
your call site depends on a faithful null round-trip.

---

### 11. Parameterised Collection / Map subtype pairs across DIFFERENT raw classes are rejected

**Severity:** P1 — the most common MapStruct migrator shape: a JPA entity declares `List<X>` (interface) and the DTO
declares `ArrayList<Y>` (concrete impl), or vice versa. Hits adopters on day one of migration.

**Reproduction:**

```java
class Outer {
  private List<Inner> items;
  public List<Inner> getItems() { return items; } public void setItems(List<Inner> items) { this.items = items; }
}
class OuterDto {
  private ArrayList<InnerDto> items;
  public ArrayList<InnerDto> getItems() { return items; } public void setItems(ArrayList<InnerDto> items) { this.items = items; }
}
record Inner(String id) {} record InnerDto(String id) {}

Telescope.mapper(Outer.class, OuterDto.class, writeBeans(SETTERS));
// IllegalStateException: Deep map: component 'items' has incompatible source/target shapes —
// java.util.List<Inner> vs java.util.ArrayList<InnerDto>.
```

**Root cause:** `DeepMap.ContainerShape.of` only recognised exact-match raw classes (`raw == List.class`,
`raw == Map.class`, etc.). For `ArrayList<Y>` the call returned null, so the autoIso container-match branch never fired
— the throw at the end of `computeAutoIso` was the fallback. Bug 7 fixed the _raw-subtype-on-both-sides_ case
(`class ImageUrls extends ArrayList<X>` on both sides via `collectionCopyIso`) but not the parameterised case.

**Expected:** the parameterised subtype pair is recognised as a same-kind container, the element-iso is lifted into a
target-concrete-class-aware allocator, and `forward()`/`backward()` produce instances of the declared raw class on each
side. MapStruct's generated mappers do this with a simple for-loop.

**Fix:**

- `core/.../DeepMap.java` — `ContainerShape` extended to carry the raw class and accept any subtype of `List` / `Set` /
  `Map` via `isAssignableFrom`. (Optional stays exact-match — it's final.)
- `core/.../DeepMap.java` — new `liftListIntoTargetRaw` / `liftSetIntoTargetRaw` / `liftMapIntoTargetRaw` helpers that
  allocate fresh instances of the target's concrete raw class via `Beans.intermediateAllocator`, with explicit fallbacks
  for the common JDK Collection / Map types (`ArrayList`, `LinkedList`, `ArrayDeque`, `HashSet`, `LinkedHashSet`,
  `TreeSet`, `HashMap`, `LinkedHashMap`, `TreeMap`, `ConcurrentHashMap`, etc.) since `LambdaMetafactory.privateLookupIn`
  can't bind classes in `java.base`. User-defined subclasses go through `intermediateAllocator` as before.

**Limitation by design:** the same-kind gate accepts any (List, List) / (Set, Set) / (Map, Map) pair, but mismatched
ordering / threading semantics within a kind (e.g. `LinkedHashMap → HashMap` reorders, `ConcurrentHashMap → HashMap`
drops the concurrency contract) are silent — same trade-off as Bug 7's `sameKindMap`. Adopters needing strict
preservation declare an explicit `Mapping.via(...)` row.

**`EnumMap` targets are rejected at plan-time.** `EnumMap` has no no-arg constructor (it requires `Class<K>` to know the
enum key class), and the auto-Iso lift can't recover the key class at allocation time. The mapper throws an
`IllegalStateException` at construction with a precise diagnostic. Adopters using `EnumMap` target fields must use the
codegen path (where the key class is captured at compile time) or an explicit `Mapping.via(...)` row that constructs the
EnumMap with its key class. Same applies to any user-defined Collection/Map subclass without a public no-arg constructor
— caught at plan-time with a class-named diagnostic, no silent surprise.

---

### 12. `@BeanFocus` codegen: no null-guard on `Integer → int` in generated `construct()` — Fixed (v1.0.4)

**Severity:** P1 — NPE at runtime when source has a nullable `Integer` field and target has a primitive `int`.

**Version:** Found in 1.0.1 — sibling of Bug 3 (primitive ↔ wrapper). Bug 3 was fixed for the runtime `DeepMap` path,
but the `@BeanFocus`-generated `*FieldOptics.construct()` code still emits a raw boxed cast that NPEs on auto-unboxing.

**Reproduction:**

```java
@Data
@BeanFocus
public class AlertRequest {

  private int attemptCount; // primitive
}

@Data
@BeanFocus
public class GovtIdDBDataIndex {

  private Integer docUpldAtmpCnt; // boxed, nullable
}

// Runtime path (DeepMap) handles this correctly in 1.0.1.
// But when @BeanFocus generates AlertRequestFieldOptics, its construct() does:
//   setAttemptCount((Integer) valueByName.apply("attemptCount"))
// When valueByName returns null → NPE on the implicit Integer → int unboxing.
```

**Observed:**

```
NullPointerException: Cannot invoke "java.lang.Integer.intValue()" because the return value of
"java.util.function.Function.apply(Object)" is null
   at AlertRequestFieldOptics.construct(AlertRequestFieldOptics.java:133)
```

**Root cause:** `AbstractTelescopeProcessor#emitBeanConstruct` (codegen/.../AbstractTelescopeProcessor.java:952) emits

```java
c.setAttemptCount((Integer) values.apply("attemptCount"));
```

When the property's underlying type is a primitive, `boxedType(p.type())` returns the boxed name (e.g. `Integer`) for
the cast, but the setter accepts `int`. Auto-unboxing of a `null` boxed value crashes. The runtime `SettersWriter`
already handles this (Bug 8 fix), but the codegen-emitted path bypasses it.

**Expected:** Generated `construct()` should null-guard the unbox and substitute the JLS default for the primitive type
(the same substitution `NullDefaults` performs on the runtime path):

```java
final var v = values.apply("attemptCount");
c.setAttemptCount(v != null ? (int) v : 0);     // ← null-guard + JLS default
```

**Workaround:** Use explicit `forward()` rows with null-guard lambdas instead of letting the codegen path emit the
constructor body:

```java
forward(GovtIdDBDataIndex::getDocUpldAtmpCnt, AlertRequest::getAttemptCount,
        i -> i != null ? (int) i : 0)
```

**File to fix:** `codegen/src/main/java/io/github/eschizoid/telescope/codegen/AbstractTelescopeProcessor.java` —
`emitBeanConstruct(...)` template, lines 952–981. Detect primitive underlying types via the existing `Prop#type()` and
emit the null-guarded form for those properties; non-primitive properties keep the simple cast.

**Resolution:** `AbstractTelescopeProcessor#emitBeanConstruct` now routes every value-extract through a single
`valueExprForProp(Prop)` helper. For primitive-typed properties the emitted form is
`c.setX(values.apply("x") instanceof Integer __v ? __v : 0)` — the `instanceof` pattern null-guards the unbox and a
companion `primitiveDefaultLiteral(TypeMirror)` table substitutes the right JLS-default literal for each of the eight
primitive kinds. Reference-typed properties keep the plain cast form, since `null` is a legal argument to a
reference-typed setter. Both rebuild strategies (no-arg ctor + setters; static `builder()` chain) take the guarded form
uniformly, and `BeanFocusProcessor` + `LombokFocusProcessor` both benefit because they share the template.

---

### 13. `@BeanFocus` generated `FieldOptics` NPEs on null intermediate objects — Fixed (v1.0.4)

**Severity:** P1 — NPE at runtime when navigating through a nullable nested object that has `@BeanFocus`.

**Version:** Found in 1.0.3 — sibling of Bug 4 (null intermediates). Bug 4 was fixed for the runtime
`Beans.readProperty()` path, but the `@BeanFocus`-generated `*FieldOptics` code bypasses that path entirely.

**Reproduction:**

```java
@DynamoDBDocument
@BeanFocus
public class DocumentDataDto implements Serializable {

  private AddressDto addressDto;
  private BiographicDto biographicDto;
}

@Data
@BeanFocus
public class GovtIdDBDataIndex {

  private DocumentDataDto documentDataDto; // may be null in test/sparse data
}

// When source.getDocumentDataDto() is null, the generated FieldOptics
// tries to call methods on null:
//   DocumentDataDtoFieldOptics.get(null, "addressDto") → NPE
```

**Observed:**

```
NullPointerException
   at DocumentDataDtoFieldOptics.get(...)
   at Reflective.lambda$structuralIsoArr$5(...)
   at Iso$1.from(...)
```

**Root cause:** The generated `*FieldOptics` class pre-builds direct method-handle invocations for each property. When
the parent object is null (a nullable intermediate in a multi-hop path), the generated `get()` method calls the getter
on null — no null guard. The runtime path (`Beans.readProperty`) has `if (pojo == null) return null;` (Bug 4 fix), but
the generated codegen path bypasses this entirely.

**Expected:** Generated `FieldOptics.get()` should null-guard:

```java
public Object get(Object pojo, String name) {
  if (pojo == null) return null; // ← null intermediate guard
  return switch (name) {
    case "addressDto" -> ((DocumentDataDto) pojo).getAddressDto();
    case "biographicDto" -> ((DocumentDataDto) pojo).getBiographicDto();
    default -> throw new IllegalArgumentException("unknown property: " + name);
  };
}
```

**Impact:** `@BeanFocus` is only safe on top-level mapper **targets** (always freshly constructed, never null). NOT safe
on nullable nested intermediates in multi-hop telescope paths. This limits `@BeanFocus` applicability to leaf/output
classes only — a real regression vs. the runtime path, which Bug 4 made safe in v1.0.1.

**Workaround:** Only apply `@BeanFocus` to classes that are always non-null in mapper contexts (output targets, not
nullable source intermediates). For nullable intermediates, drop `@BeanFocus` and let the runtime `Beans.readProperty`
path handle navigation.

**File to fix:** `codegen/src/main/java/io/github/eschizoid/telescope/codegen/AbstractTelescopeProcessor.java` — the
`writeMetadataHolderClass(...)` body that emits the `*FieldOptics.get(...)` method on the holder. Prepend
`if (pojo == null) return null;` to the dispatch switch. `BeanFocusProcessor` and `LombokFocusProcessor` both route
through this template, so the single template fix covers both.

**Resolution:** Investigation showed the NPE wasn't in a literal `FieldOptics.get(Object, String)` method (no such
method is emitted) — the failure surfaced at the runtime `Reflective#structuralIsoArr` reader path, which dispatches the
holder's `Telescope.lens(MethodRef, …)` constants. The captured method reference NPEs on a `null` receiver. The fix
lives one layer down at the lattice: `Lens#getAll(null)` now returns `Stream.empty()` (and `Lens#getOption(null)`
returns `Optional.empty()`) instead of dispatching `get(null)`. The composed Lens-as-Traversal projection used by
`Telescope#read` / `find` short-circuits cleanly on the null intermediate. `Telescope#find` / `read` also gained a
matching null-source guard at the Lens fast-path so direct atomic holder-constant access
(`OuterFieldOptics.inner.find(null)`) no longer NPEs through the method-reference receiver.
`DeepMap#overrideTargetField` switched from `srcT.read(s)` to `srcT.find(s).orElse(null)` so the now-empty stream
resolves to a `null` target value instead of throwing `NoSuchElementException`. Atomic `lens.get(null)` still NPEs when
the user's getter does — strict-lens semantics for direct gets are unchanged.

---

### 14. `DeepMap.applyForward()` missed the null-safety fix on the `TelescopeToTelescope` path — Fixed (v1.0.5)

**Severity:** P1 — regression in 1.0.5 for any mapping that reads through a `to(Telescope, Telescope)` row over a source
whose nested intermediate is `null`. Works on 1.0.4, crashes on 1.0.5 (post-Bug-13 fix).

**Reproduction:**

```java
@Data
public class Source {
  private NestedData nestedData; // null in sparse test data
}

@Data
public class NestedData {
  private String value;
}

@Data
public class Target {
  private String flatValue;
}

Telescope.mapperForward(Source.class, Target.class,
  to(Telescope.ofBean(Source.class).field(Source::getNestedData).field(NestedData::getValue),
     Telescope.ofBean(Target.class).field(Target::getFlatValue)),
  writeBeans(SETTERS));

// When source.nestedData is null:
mapper.forward(source);   // NoSuchElementException!
```

**Observed:**

```
java.util.NoSuchElementException: Telescope has no value in this source (path starts at field 'getNestedData')
   at io.github.eschizoid.telescope.Telescope.noValue(Telescope.java:1121)
   at io.github.eschizoid.telescope.Telescope.read(Telescope.java:1102)
   at io.github.eschizoid.telescope.DeepMap.applyForward(DeepMap.java:745)
```

**Root cause:** Bug 13 (PR #154) fixed `DeepMap#overrideTargetField` at line 835 (the `FromTelescopeTo` row path) to use
`srcT.find(s).orElse(null)`. The sibling site at line 745 (the `TelescopeToTelescope` forward-overlay path in
`applyForward`) was missed — it still calls `srcT.read(s)`, which now surfaces the improved `firstHopName` diagnostic
but still throws.

**Codebase audit** (Round 3 feedback intake): swept `DeepMap.java` for every Telescope read site that participates in
the forward direction. Found:

- **Line 745** — `TelescopeToTelescope` BROADCAST forward overlay: ❌ unfixed, exact mirror of the line 835 pattern.
- **Line 835** — `FromTelescopeTo` forward overlay: ✓ already fixed in PR #154.
- **Line 779** — `TelescopeTo` backward overlay: safe (reads from target, not source).
- **Line 807** — `TelescopeToTelescope` backward overlay: safe (reads from target, not source).

Single missed site, no other latent bugs.

**Fix:** Line 745 change from `t = tgtT.set(t, srcT.read(s));` to `t = tgtT.set(t, srcT.find(s).orElse(null));`,
matching the line 835 form. One-line patch + a regression test in `MigrationRegressionTest` pinning the
`to(Telescope, Telescope)` shape.

**Resolution:** Applied the one-line fix at `DeepMap.java:745`. Both forward call sites now use the lenient
`find().orElse(null)` form; the backward call sites stay strict because they read from the freshly-built target where a
null intermediate is a real invariant violation. Regression test in `MigrationRegressionTest` pins the new contract.

### 15. `@BeanFocus` write-path: nested intermediates beyond the first hop are not auto-constructed — Fixed (v1.0.7)

**Severity:** P1 — crashes at runtime when `@BeanFocus` is applied to classes used as nullable intermediates in
multi-hop target telescope paths. Read-path null-safety (Bug 13 / PR #154) was complete; the write path was only handled
at the first hop.

**Reproduction:**

```java
@BeanFocus
public class Outer { Mid mid; ... } // mid nullable

@BeanFocus
public class Mid { Leaf leaf; ... } // leaf nullable

@BeanFocus
public class Leaf { String value; ... }

final var tgt = Telescope.ofBean(Outer.class)
    .field(Outer::getMid)
    .field(Mid::getLeaf)
    .field(Leaf::getValue);

Telescope.mapperForward(Src.class, Outer.class,
    to(srcLeaf, tgt),
    writeBeans(SETTERS));

mapper.forward(src);  // NPE: freshly-constructed Outer has mid = null
```

**Observed (Layer 1 — `Lens.modify` NPE):**

```
java.lang.NullPointerException
   at io.github.eschizoid.telescope.internal.optics.Lens$1.get(Lens.java:103)
   at io.github.eschizoid.telescope.internal.optics.Lens.modify(Lens.java:52)
   at io.github.eschizoid.telescope.internal.optics.Traversal$2.lambda$modify$0(Traversal.java:113)
   ...repeated for each hop...
   at io.github.eschizoid.telescope.Telescope.set(Telescope.java:1288)
   at io.github.eschizoid.telescope.DeepMap.applyForward(DeepMap.java:749)
```

After null-guarding `Lens.modify`, the second-layer failure (silent skip — leaf write is dropped because intermediates
are never constructed) surfaces.

**Root cause — two issues:**

1. **`Lens.modify` calls `get(source)` without a null guard.** When the composed traversal chain descends through a null
   intermediate during `tgtT.set(t, value)`, `modify(null, fn)` invokes `get(null)` and NPEs on the LMF-bound getter
   receiver.
2. **Holder-backed structural Iso resolution skips recursive intermediate seeding.** The non-holder
   `DeepMap.populateIso` path recursively descends into nested type pairs and seeds `placeholderIsoFor(..., true)`
   defaults at every hop claimed by telescope writes. The holder-backed fast path (triggered when `@BeanFocus` provides
   a `*FieldOptics` constant) short-circuits this recursion — only the first hop gets a default allocator. Hops beyond
   the first inherit `null` from the bean's no-arg ctor, and the write descent walks straight into them.

**Why PR #154 missed this:** PR #154 fixed the **read** projection paths — `Lens#getAll(null)` → `Stream.empty()`,
`Lens#getOption(null)` → `Optional.empty()`, `Telescope#find(null)` → `Optional.empty()`, `DeepMap#overrideTargetField`
→ `find().orElse(null)`. `Lens#modify` was intentionally left strict ("strict atomic `lens.get(null)` still NPEs —
direct-get semantics unchanged"). That decision is correct for atomic gets, but `modify` is called by the **write** path
(`Traversal.modify` → `Lens.modify` → `get + set`) where null intermediates are a normal condition during target
construction.

**Fix scope:** Layer 1 + Layer 2 together — null-guarding `Lens.modify` without the recursive auto-construction would
turn the NPE into a silent write-skip, which is worse than the crash. The holder-backed structural Iso path must
recursively auto-construct nested intermediates the same way `DeepMap.populateIso` does for the non-holder path. The fix
generalises to N-hop paths, not just 3.

**Files involved:**

- `internal/src/main/java/io/github/eschizoid/telescope/internal/optics/Lens.java` — `modify()` (line 52).
- `core/src/main/java/io/github/eschizoid/telescope/DeepMap.java` — `populateIso()` recursive descent +
  `placeholderIsoFor` + `telescopeWritesTgt` logic (lines ~385-550, ~1918-1931).
- `core/src/main/java/io/github/eschizoid/telescope/Telescope.java` — `holderReadersFor()` (lines ~1741-1752),
  `Telescope.set` (line ~1288).

**Resolution:** _(populated once the fix lands — keeping this entry in sync with the implementing PR.)_

---

### 16. `@BeanFocus` multi-property codegen setter NPEs on a null intermediate beyond the first hop — Fixed (v1.0.8)

**Severity:** P1 — crashes at runtime when a **multi-property** `@BeanFocus` class is a nullable intermediate at hop 2
or deeper in a write-through path. Sibling of Bug 15: the `Lens.modify` null-source fix (v1.0.7) landed correctly, but
the generated per-field lens still reads off-path properties off a `null` previous instance.

**Reproduction:**

```java
@BeanFocus
public class Address {          // multi-property
    String cityName;            // the write targets this
    String countryName;         // off-path
    int zipCode;                // off-path (primitive)
}

@BeanFocus public class Mid { Address address; }     // single-property hop-1 intermediate
@BeanFocus public class Outer { Mid mid; }           // freshly built -> mid and address both null

final var tgt = Telescope.ofBean(Outer.class)
    .field(Outer::getMid)
    .field(Mid::getAddress)
    .field(Address::getCityName);

Telescope.mapperForward(Src.class, Outer.class, to(srcLeaf, tgt), writeBeans(SETTERS));
mapper.forward(src);            // NPE in the generated AddressFieldOptics.cityName lens
```

**Observed:**

```
java.lang.NullPointerException: Cannot invoke "Address.getCountryName()" because "p" is null
   at AddressFieldOptics.lambda$static$0(AddressFieldOptics.java:12)
   at io.github.eschizoid.telescope.internal.optics.Lens$1.set(Lens.java:158)
   at io.github.eschizoid.telescope.internal.optics.Lens.modify(Lens.java:102)
   at io.github.eschizoid.telescope.Telescope.set(Telescope.java:1288)
   at io.github.eschizoid.telescope.DeepMap.applyForward(DeepMap.java:749)
```

**Root cause:** the codegen lens rebuild reads every off-path property off the previous instance to carry it forward:
`(p, v) -> { var c = new Address(); c.setCityName(v); c.setCountryName(p.getCountryName()); ...; return c; }`. When the
intermediate is a null write-target the previous instance `p` is null, so each off-path `p.getX()` NPEs. At hop 1 the
Bug 15 seeding constructs a fresh non-null intermediate before the leaf lens runs, so the read never sees null — which
is why a hop-2+ multi-property bean is required to trigger it.

**Why Bugs 13 and 15 missed it:** every regression fixture for the null-intermediate write path was **single-property**
(`Leaf { String value; }`). A single-property lens has no off-path read —
`(p, v) -> { var c = new Leaf(); c.setValue(v); return c; }` — so it tolerates a null `p` regardless. The crash only
manifests once a multi-property bean is reached through a null intermediate, a shape no fixture exercised.

**Fix:** null-guard each off-path read in the generated lens, for both rebuild strategies (no-arg ctor + setters and
static `builder()`): `c.setCountryName(p == null ? null : p.getCountryName())`, primitive off-path reads taking their
JLS-default literal (`0` / `false`) instead of `null`. This matches the reflective `SettersWriter` rebuild, which
already leaves off-path properties at their defaults when the source is null. The focused property always takes the
incoming value, so single-property beans emit no guard and are unchanged. The guard is per-lens and gated only on
`p == null`, so it holds at arbitrary nesting depth (N-hop, pinned by a hop-3 regression test).

**Files involved:**

- `codegen/src/main/java/io/github/eschizoid/telescope/codegen/AbstractTelescopeProcessor.java` — `beanRebuild()` (the
  per-field lens emission) + new `offPathRead()` helper reusing `primitiveDefaultLiteral()`.
- `internal/src/main/java/io/github/eschizoid/telescope/internal/optics/Lens.java` — `modify()` javadoc (the
  "multi-property null intermediates still crash loudly … out of scope" note is now obsolete and corrected).

**Note on records:** the `@Focus` canonical-ctor lens (`(s, v) -> new R(v, s.other())`) has the analogous off-path read,
but record write-through-a-null-intermediate stays strict by design (an immutable record cannot be partially
constructed); that remains documented as intentional in `Lens.java` and is out of scope here.

**Resolution:** _(populated once the fix lands — keeping this entry in sync with the implementing PR.)_

---

## Enhancement Requests

### 1. Cross-module `@Bridge` support (MapStruct parity) — Fixed (v1.0.2)

**Problem:** `@Bridge` must live on the model class, which constrains it to types visible from that class's own Maven
module. When source and target live in different modules without a dependency path, the annotation can't be placed on
either side.

**How MapStruct solves it:** The `@Mapper` interface is a standalone file in the consuming module (which depends on all
needed modules). The model classes stay annotation-free.

**Resolution:** Added carrier-form `@Bridge` — when `source = ...` and `target = ...` are set, the annotation can sit on
a third "carrier" class that lives in a module seeing both sides:

```java
@Bridge(
  source = IdentityDocumentDBDetails.class,
  target = IdentityDocumentDetailsBO.class,
  renames = { @Rename(source = "icVerificationExt", target = "vendorExtendedResult") }
)
public class IdentificationBridgeDef {}
```

`BridgeProcessor` emits `IdentificationBridgeDefBridge.BRIDGE` in the carrier's package (NOT the source's — the source's
module can't see the carrier). `value()` now defaults to `Void.class` so carrier form can omit it; bare `@Bridge` still
surfaces a precise error per a regression guard. Model-anchored form is unchanged. ADR-0007. PR #149.

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

### 4. Annotation processor ordering documentation — Fixed (v1.0.2)

**Problem:** When Lombok and telescope-lombok are both on the annotation processor path, processor ordering matters.
`LombokFocusProcessor` correctly defers via `processingOver()`, but `BridgeProcessor` did NOT defer — it processed
immediately and saw "known fields: []" on Lombok-generated targets.

**Resolution:**

1. README now carries an "Annotation processor ordering with Lombok" sub-section: recommended Lombok-first ordering
   posture, the broader `LOMBOK_SYNTHESIZING_ANNOTATIONS` trigger set, and symptoms of mis-ordering.
2. `BridgeProcessor` now round-defers emission when source or target carries any Lombok-synthesizing annotation —
   mirrors `LombokFocusProcessor`'s pattern. Trigger set is broader than `LOMBOK_BEAN_ANNOTATIONS`: also includes
   `@Getter`, `@Setter`, the three `*ArgsConstructor` variants, `@SuperBuilder`, `@With`, `@experimental.Accessors`,
   `@experimental.FieldDefaults`. The build is order-tolerant — explicit Lombok-first ordering is still recommended
   posture but no longer required for correctness. PR #143.

---

### 5. Untyped source mapping (`Map<String, Object>` → POJO) — Fixed (v1.0.2)

**Problem:** No first-class support for mapping from untyped sources (raw Maps, JSON nodes). Common in legacy code that
receives `Map<String, Object>` from frameworks.

**Resolution:** New sealed `MapExtractStep` interface + `Extract` permit + static `extract(...)` factory +
`Telescope.fromMap(Class<T>, MapExtractStep...)` returning `ForwardMapper<Map<String, Object>, T>`:

```java
import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;

ForwardMapper<Map<String, Object>, CaseListRequest> m = Telescope.fromMap(
  CaseListRequest.class,
  extract("bookingType", CaseListRequest::getBookingType, Object::toString),
  extract("caseId", CaseListRequest::getCaseId, Object::toString),
  extract("priority", CaseListRequest::getPriority, (v) -> Integer.parseInt(v.toString()))
);
```

Lenient by default: missing keys + unmatched target components take their `NullDefaults` value (`""` for `String`, `0`
for numeric primitives, immutable empty singletons for collections, etc.). Extra map keys are silently ignored. Bean
getters normalize through `Beans.propertyOf` (`X::getEmail` reaches the `email` property). Backward direction NOT
generated by design. Routes through existing `Records.construct` / `Beans.autoWriter` substrate — no new internal
machinery. ADR-0008. PR #150.

---

### 6. `@Bridge` lenient mode — skip unmatched fields — Fixed (v1.0.2, pending PR #148)

**Problem:** `@Bridge` enforces strict bijection. If the target has 130 fields that the source doesn't provide, you'd
need 130 `@Constant(field = "x", value = "null")` entries — completely impractical.

**Real-world pattern:** `CustomerCaseRequest` (7 fields) → `GovtIdDBData` (135 fields). Only 6 fields actually map; the
other 129 should stay at JLS defaults.

**Resolution:** New `lenient` attribute on `@Bridge` (default `false` preserves the historical strict-bijection
contract):

```java
@Bridge(value = GovtIdDBData.class, lenient = true,
        renames = {@Rename(source = "referenceID", target = "entRefncId")})
```

When `lenient = true`, `BridgeProcessor` auto-extends `drops` with source fields that have no target counterpart, and
synthesises JLS-default constants (via `defaultLiteralFor(TypeMirror)`) for target fields with no source counterpart.
The existing bijection check passes naturally against the auto-extended sets — no parallel codegen path. Renames and
transforms still go through their normal type-safety pipeline.

**Round-trip-loss warning, by direction name.** `lenient = true` produces a partial-Iso whose
`BRIDGE.set(source, target)` direction is the lossy one — every `Source`-side field with no `Target` counterpart comes
back at its JLS default regardless of what the original Source held. `BRIDGE.read(source)` (forward) is fine. Adopters
who rely on backward round-trip safety must NOT set `lenient`. The `@Bridge#lenient` javadoc spells this out next to the
attribute declaration, naming `BRIDGE.set(source, target)` explicitly so the asymmetry is unambiguous. ADR-0009.

---

### 7. `Sources.byClass()` type safety — Fixed (v1.0.2)

**Problem:** `Sources.byClass(Class<T>)` returns `Object` requiring a cast:

```java
final var headers = (PolicyRequestHeaders) sources.byClass(PolicyRequestHeaders.class);
```

**Resolution:** `Sources.byClass(...)` now carries a `<T>` type parameter and returns `T` directly. Erasure makes the
signature change binary-compatible — pre-existing call sites that explicitly cast `(PolicyRequestHeaders)` still compile
and run. New call sites can drop the cast: `final PolicyRequestHeaders headers = sources.byClass(...)`. The lookup
remains EXACT runtime-class match (pinned by `SourcesByClassGenericsTest#byClassExactRuntimeClassMatchOnly`); no
`Class.isAssignableFrom` widening was added so the contract stays narrow. PR #140.

---

### 8. `Mapping.forward()` naming — Fixed (v1.0.2)

**Problem:** `Mapping.forward(src, tgt, fn)` is the forward-only row factory. But when static-imported alongside
`Mapping.to()`, the name `forward` conflicts with common variable/method names and is less expressive than `to()`.

**Resolution:** Renamed `Mapping.forward(...)` → `Mapping.toOneWay(...)` as a clean rename (no `@Deprecated` alias).
`toOneWay` reads as the unidirectional sibling of `to(...)` — both factories produce a row keyed off a
`(sourceAccessor, targetAccessor)` pair; `to` is bidirectional, `toOneWay` carries only a forward function. The
call-site collision with local variables named `forward` / `forwardEvent` is gone, and the symmetry with `to(...)` aids
discovery. The `ForwardOnlyTransformTo` sealed-permit record is unchanged; only the factory name flipped. PR #140.

---

### 9. `mapperForward()` should be lenient by default — Fixed (v1.0.2)

**Problem:** `Telescope.mapperForward()` ran the same strict bijection check as `Telescope.mapper()` — it threw at
construction time if the target had properties with no same-name source property:

```
IllegalState: Deep map DocumentT → IdentificationResponse: target property 'documentStatus'
has no same-name source property. Add a rename row to(sourceAccessor, targetAccessor)
that maps to 'documentStatus'.
```

A `ForwardMapper<A, B>` is explicitly one-directional; the bijection invariant (round-trip losslessness) doesn't apply.
Real-world impact: a 3-field rename between two beans with disjoint field sets required 13 `drop()` + 2 `constant()`
rows just to satisfy the strict check — turning a 3-line mapper into a 20-line mapper. MapStruct's default for every
generated mapper is silent JLS-default fill on the target plus silent ignore on the source.

**Resolution:** `DeepMap.resolveForward(...)` threads a `lenient=true` flag through `populateIso`. Both unmatched-field
gates now fire under `lenient || isNested || telescope-fixups`. `Telescope.mapperForward(...)` routes through the new
lenient path; `Telescope.mapper(...)` keeps the strict bijection check for round-trip safety. Pinned by the
`MapperForwardLenientByDefault` nested test in `MigrationRegressionTest`. PR #138.

**Interaction with Bug 6.** Bug 6 made NESTED auto-recursed pairs lenient regardless of the top-level call's strictness;
Enh 9 makes TOP-LEVEL forward-only calls lenient. The two axes don't overlap: bidirectional `Telescope.mapper(...)`
stays strict at the top level (Bug 6's nested leniency still applies on its recursive descent), and forward-only
`Telescope.mapperForward(...)` is lenient at every level (top via `lenient=true`, nested via `isNested`). The recursive
`populateIso` call passes `lenient=false` so the flag's meaning stays anchored to the user-facing top-level entry point.
No double-leniency path is constructible.

---

### 10. `:internal` test coverage hardening — Fixed (v1.0.2)

**Source:** codecov report on the `:internal` module post-v1.0.1 release. Migration-feedback bugs that would have been
caught earlier are pinned by end-to-end `MigrationRegressionTest`, but unit-level coverage on the substrate was thin —
future refactors of the LMF cache, the writer strategies, or the reflection helpers had less protection than they
should.

**Resolution:** Added meaningful unit tests across the highest-gap targets — every test pins a real contract or edge
case (per the project's "meaningful tests over coverage-bait" rule):

- `NullDefaultsTest` (9 tests, ~100% instructions): JLS-default substitution table — primitive wrappers, enterprise
  columns (`String`/`BigDecimal`/`BigInteger`), container singletons + immutability pins, deliberate-absence of
  `Character`, `ParameterizedType` unwrap, catch-all behaviour for unknown leaf types.
- `LambdaIntrospectionTest` (9 tests, ~86% instructions): `methodNameOf` / `implClassOf` recovery + cache identity +
  inherited-getter limitation pin + lambda-rejection error wording.
- `MetadataHolderProbeShapeCheckTest` (5 tests, ~89% instructions): bound-constructor end-to-end (Phase B's runtime
  payoff) + 4 hand-rolled malformed-holder fixtures pinning the precise codegen-out-of-sync diagnostics.

PR #144.

---

### 11. `Telescope.merge()` requires `@SuppressWarnings("unchecked")` at every call site — Verified (v1.0.5)

**Source:** Round 3 adopter feedback (v1.0.5 intake).

**Problem:** Every `Telescope.merge(target, MergeStep<T>...)` call site triggers Java's unchecked-warning on
generic-array creation because the varargs slot is `MergeStep<T>...`. Adopters either silence the warning with
`@SuppressWarnings("unchecked")` per call site or live with the warning. The original feedback proposed a parallel
`Telescope.mergeBuilder(...)` API (`.from(...).build()...`) that sidesteps varargs entirely.

**Adopter's proposed shape:**

```java
@SuppressWarnings("unchecked")
private static final Mapper<Sources, ICRetailIDServiceRequest> MAPPER =
    Telescope.merge(ICRetailIDServiceRequest.class,
        from(IdentificationRequest::getDocumentMetaData, ...),
        from(ICRetailIDServiceRequest::getRetailIDLoginRequest))
        .afterForward(...);

// Proposed builder alternative:
private static final Mapper<Sources, ICRetailIDServiceRequest> MAPPER =
    Telescope.mergeBuilder(ICRetailIDServiceRequest.class)
        .from(IdentificationRequest::getDocumentMetaData, ...)
        .from(ICRetailIDServiceRequest::getRetailIDLoginRequest)
        .build()
        .afterForward(...);
```

**Analysis (declined the proposed shape, accepted the underlying pain):** The pain is real, but a parallel builder API
is the wrong fix. A `mergeBuilder(...)...build()` chain reads longer than the varargs form (extra `.build()` step, extra
vocabulary to learn for a feature equivalent to the varargs one), and adding a second way to do the same thing is API
bloat — exactly the kind of thing that hurts the world-class-ergonomics mantra it claims to advance. The real fix is
`@SafeVarargs` on the existing `Telescope.merge` method: the implementation only iterates the array (no writes, no
leaking the reference outside the method body), so `@SafeVarargs` is genuinely safe; applying it eliminates the
unchecked warning at every call site without introducing a new API surface.

**Resolution:** `Telescope.merge` carries both `@SafeVarargs` and `@SuppressWarnings("varargs")`; the parallel
`Telescope.all(Edit<S>...)` entry point is similarly annotated. Adopter call sites invoking `Telescope.merge(...)`
directly should not require their own `@SuppressWarnings("unchecked")`. If a warning still surfaces in the field, the
most likely cause is a generic-varargs site elsewhere in the mapping pipeline (post-mapping hooks, sources builders,
custom row factories) that lacks the annotation — file as a separate bug naming the offending entry point.

The `mergeBuilder(...)` shape from the proposal is intentionally not added — the underlying warning concern is addressed
at the library boundary, no parallel API needed.

---

### 12. `Telescope.fromMap()` should compose for nested map → sub-POJO extraction — Proposed (v1.0.6)

**Source:** Round 3 adopter feedback (v1.0.5 intake).

**Problem:** `Telescope.fromMap(Class<T>, MapExtractStep...)` only supports flat key-value extraction. When a
`Map<String, Object>` carries a nested `Map<String, Object>` value that itself needs to be converted into a sub-POJO,
adopters must write a manual static method as the converter — boilerplate that breaks the otherwise-declarative
`extract(key, accessor, converter)` shape. Real use cases include DynamoDB AttributeValue maps and JSON payloads with
nested objects.

**Adopter's proposed shape:**

```java
extract("pageDetails", CaseListRequest::getPageDetails,
    Telescope.fromMap(PageDetails.class,
        extract("pageSize", PageDetails::getPageSize, obj -> (int) obj),
        extract("exclusiveStartKey", PageDetails::getExclusiveStartKey, obj -> processKey(obj))))
```

**Analysis (worth doing, contingent on composition check):** Real use case, but the existing API surface may already
compose cleanly with a single cast at the lambda boundary (`obj -> mapper.forward((Map<String, Object>) obj)`). If that
composition works, the gap is small — the proposed `nested(...)` factory only saves the `(Map<String, Object>)` cast. If
composition is genuinely blocked (e.g., the inner `extract` factories don't return the right shape for the outer
converter slot), a `nested(Class<T>, MapExtractStep...)` factory that returns a `Function<Object, T>` (doing the cast
internally) is justified. Half-day of investigation + delivery either way.

**Status:** Deferred to v1.0.6 — investigate composition first.

---

### 13. `Telescope.mapperForward()` should auto-discover `@Bridge`-generated bridges — Proposed (v1.0.6)

**Source:** Round 3 adopter feedback (v1.0.5 intake).

**Problem:** `@Bridge(value = Target.class, renames = {...})` generates a `<Source>Bridge.BRIDGE` constant at compile
time, but:

1. The constant cannot be imported from source in the same module (deferred to `processingOver()` by design — the
   round-deferred emission pattern).
2. `Telescope.mapperForward()` does NOT auto-discover it — it builds its own mapper from `to()` rows.

This means the mapping definition is duplicated: once in the `@Bridge` annotation (renames) and again in the
`mapperForward()` call (`to()` rows). The MapStruct equivalent (`@Mapper`) auto-resolves; not having parity here is a
real friction point.

**Adopter's proposed shape:**

```java
@Bridge(value = GovtIdDBData.class, lenient = true, renames = {
    @Rename(source = "referenceID", target = "entRefncId"),
    @Rename(source = "businessUnit", target = "busUnitNm"),
    @Rename(source = "caseId", target = "busCaseId")
})
public class CustomerCaseRequest { ... }

// No to() rows needed — auto-discovers CustomerCaseRequestBridge.BRIDGE:
@Bean
public ForwardMapper<CustomerCaseRequest, GovtIdDBData> mapper() {
    return Telescope.mapperForward(CustomerCaseRequest.class, GovtIdDBData.class, writeBeans(SETTERS));
}
```

**Analysis (worth doing, high impact):** This is the only one of the three Round 3 enhancements that meaningfully moves
the MapStruct-parity needle. The implementation can mirror the existing `MetadataHolderProbe` pattern that already
discovers `@Focus` / `@BeanFocus` holders at runtime — `Telescope.mapperForward(A.class, B.class, ...)` probes the
classpath for a sibling `<A>Bridge` constant via a `ClassValue<Optional<HolderRef>>` cache; when present, route the
forward direction through `<A>Bridge.BRIDGE`. Explicit `to()` rows become per-field overrides on top of the bridge.

**Status:** Targeted for v1.0.6 — substantial work, but well-shaped via the existing holder-probe substrate.

---

## Additional Fixes Applied During Migration (already-fixed in v1.0.1 — recorded for traceability)

| Fix                          | File                      | Description                                                                                          |
| ---------------------------- | ------------------------- | ---------------------------------------------------------------------------------------------------- |
| `Telescope.read()` null-safe | `core/.../Telescope.java` | `optic.getAll(source).findFirst()` NPEs on null elements in a Traversal — now uses a stream iterator |

---

## Summary — Bugs

| #      | Description                                                                  | Severity | Status         |
| ------ | ---------------------------------------------------------------------------- | -------- | -------------- |
| Bug 1  | ClassCastException on unresolvable `@Bridge` target                          | P1       | Fixed (v1.0.1) |
| Bug 2  | LambdaIntrospection NPE on `is*` boolean accessors                           | P0       | Fixed (v1.0.1) |
| Bug 3  | No autoboxing between primitive ↔ wrapper types                              | P1       | Fixed (v1.0.1) |
| Bug 4  | NPE on null intermediate objects in nested paths                             | P1       | Fixed (v1.0.1) |
| Bug 5  | SettersWriter throws on getter-only properties                               | P1       | Fixed (v1.0.1) |
| Bug 6  | DeepMap strict bijection enforced on nested auto-recursed types              | P1       | Fixed (v1.0.1) |
| Bug 7  | LambdaMetafactory fails on classes extending JDK types                       | P1       | Fixed (v1.0.1) |
| Bug 8  | SettersWriter NPE when valueByName returns null for primitive                | P1       | Fixed (v1.0.1) |
| Bug 9  | `forward(null)` / `backward(null)` NPE instead of returning null             | P1       | Fixed (v1.0.1) |
| Bug 10 | `fieldByName(String)` uses `Records.fieldLens()` for POJOs too               | P1       | Fixed (v1.0.1) |
| Bug 11 | Parameterised Collection / Map subtype pairs across raw classes              | P1       | Fixed (v1.0.1) |
| Bug 12 | `@BeanFocus` codegen `construct()` doesn't null-guard `Integer → int`        | P1       | Fixed (v1.0.4) |
| Bug 13 | `@BeanFocus` generated `FieldOptics` NPEs on null intermediates              | P1       | Fixed (v1.0.4) |
| Bug 14 | `DeepMap.applyForward` missed null-safety fix on `TelescopeToTelescope` path | P1       | Fixed (v1.0.5) |
| Bug 15 | `@BeanFocus` write path skips nested intermediates beyond the first hop      | P1       | Fixed (v1.0.7) |

## Summary — Enhancements

| #      | Description                                                             | Priority | Status            |
| ------ | ----------------------------------------------------------------------- | -------- | ----------------- |
| Enh 1  | Cross-module `@Bridge` carrier                                          | High     | Fixed (v1.0.2)    |
| Enh 2  | `ForwardMapper.liftList()`                                              | Medium   | Fixed (v1.0.2)    |
| Enh 3  | `Telescope.asForwardMapper()`                                           | Low      | Fixed (v1.0.2)    |
| Enh 4  | Processor ordering docs + BridgeProcessor deferral                      | Medium   | Fixed (v1.0.2)    |
| Enh 5  | `Map` → POJO factory                                                    | Low      | Fixed (v1.0.2)    |
| Enh 6  | `@Bridge` lenient mode                                                  | High     | Fixed (v1.0.2)    |
| Enh 7  | `Sources.byClass()` generics                                            | Low      | Fixed (v1.0.2)    |
| Enh 8  | `Mapping.forward()` naming                                              | Low      | Fixed (v1.0.2)    |
| Enh 9  | `mapperForward()` lenient by default                                    | High     | Fixed (v1.0.2)    |
| Enh 10 | `:internal` test coverage hardening                                     | Medium   | Fixed (v1.0.2)    |
| Enh 11 | `@SafeVarargs` on `Telescope.merge` (declined the `mergeBuilder` shape) | Low      | Verified (v1.0.5) |
| Enh 12 | `Telescope.fromMap()` nested map → sub-POJO composition                 | Medium   | Proposed (v1.0.6) |
| Enh 13 | `Telescope.mapperForward()` auto-discover `@Bridge`-generated bridges   | High     | Proposed (v1.0.6) |
