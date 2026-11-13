# A day at the shop, as a native image

The order service as one command that starts in milliseconds: it imports a partner's orders, takes a mobile-app order,
applies a PATCH, takes an order through payment and shipping, and prints the views and an invoice, checking each answer.
Compiled ahead of time, every mapper still has to work without the JVM's run-time class generation, which is why this
binary is also the native-image gate in CI.

A request's path: a partner's untyped JSON → `ImportedOrder` → `PlaceOrder` → the `Order` entity, and back out as an
`OrderDto` and an `InvoiceDto`.

```bash
./gradlew :examples:demo:native:nativeRun   # needs GraalVM's native-image; runOnJvm runs the same steps on the JVM
```

The output ends like this, one line per step:

```text
=== a day at the shop (native image) ===
PASS  the public view hides what only the shop sees, through order.toOrderDto()
PASS  a PATCH changes only what it sends, written into the entity in place
...
ALL 23 STEPS PASSED
```

This module compiles the [order-service demo](../README.md) to a GraalVM native image. It is a command, not a server, so
there is nothing to `curl`: [`OrderRun`](src/main/java/io/github/eschizoid/telescope/demo/nativeimage/OrderRun.java)
runs a day at the shop and checks every step, and the run exits non-zero when one is wrong. That makes the binary the
native-image gate in CI: [`native-image.yaml`](../../.github/workflows/native-image.yaml) builds and runs it on demand,
on pushes to `main` that touch the demo or the substrate, and weekly.

The run has two parts.

## The order service, compiled ahead of time

The first part opens the shop with its two sample orders and runs the service over its own generated mappers, hop by
hop:

1. **REST ↔ service.** The public, summary, dashboard and version 2 views go through the generated `OrderMappings`; the
   mobile app's version 2 order maps to the service's request; the partner's map binds through the generated
   `ImportedOrderFromMap`, the import's trace builds the same binding at run time and finds each line's binder through
   `ServiceLoader`, and the partner's JSON text reads through telescope-jackson's streaming binder.
2. **Service.** The PATCH lays the client's changes over the order's state with `patch`, an order is paid and shipped
   and only then shows its tracking number through the `when` row, a shipped order refuses a cancel, the invoice is
   merged from four sources and the total is computed.
3. **Service ↔ entity.** A new order's lines are added through `addLine`, and the PATCH refills the entity's own list of
   lines in place with `into`.

`RequestMappers.V2` is a two-way `Mapper` though the service only maps forward. The native run also calls its `backward`
and `patch`, so the gate covers a generated two-way field over records with no registration in all three directions.

## Native coverage beyond the service

The second part covers shapes the native-image gate has to carry that the service itself does not use, each over a small
type of this module: a record path that capitalises a city, record-to-record, record-to-bean and record-to-builder
mappers built at run time, a class-level `@Bridge` constant, a pick list cleaned by navigator paths alone, a webhook
envelope read as the class its type argument names, and a restocking batch converted through a runtime `via` row naming
a class. These are the capabilities the earlier native verifier checked, kept so the gate keeps checking them.

## What the image needs

The generated bridges and binders are plain method calls and need nothing. A mapper field's `path`, `compute` and
navigator rows are built when the class initialises, though, and the steps on the runtime path read types by reflection,
so these need the reachability metadata any GraalVM application ships for its own types, in
[`reflect-config.json`](src/main/resources/META-INF/native-image/io.github.eschizoid/telescope-demo-native/reflect-config.json)
and
[`serialization-config.json`](src/main/resources/META-INF/native-image/io.github.eschizoid/telescope-demo-native/serialization-config.json):

- the entities and the DTOs that a `path`, `compute` or `ofBean` row reads through at run time;
- the records the runtime mappers read and build, the builder class included;
- `ItemsV2` with its declared methods, which the runtime `via` row chooses from;
- the classes a row typed by a type argument loads by name, `String` among them;
- in `serialization-config.json`, every class that holds a method reference read back at run time: `OrderRun`,
  `OrderMappers` and the generated navigators.

Two steps run over types neither file names. The version 2 request and its items are converted only by the bridge
generated from `RequestMappers.V2`, whose rows are never read back. The pick list is rebuilt only through the setters
the navigators generate. A mapper that fell back to the runtime path would fail those steps.
[docs/native-image.md](../../docs/native-image.md) explains each requirement.

## Tests

[`OrderRunTest`](src/test/java/io/github/eschizoid/telescope/demo/nativeimage/OrderRunTest.java) runs every step on the
JVM, so the normal build checks them on each run.

```bash
./gradlew :examples:demo:native:test
```
