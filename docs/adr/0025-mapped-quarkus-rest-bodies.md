# ADR-0025: Mapped request and response bodies on Quarkus REST

**Status:** Accepted · **Date:** 2026-11-19 (amended 2026-11-20)

> **Amendment (2026-11-20, a pair that pairs nothing).** A body whose pair falls back to `Telescope.mapperForward`, has
> no generated bridge, and pairs no property of the target with a property of the source by name fails startup with the
> method, the pair and the properties of each side named, in the words ADR-0020's amendment gives the Spring starter:
> both starters take the rule from `EndpointMappers`. A pair where some property pairs maps as before, and a pair a
> projection, a `Mapper` bean, `using` or a generated bridge maps is never checked.

## Context

ADR-0020 and ADR-0021 removed the mapper call from the ends of a Spring MVC handler. A Quarkus REST resource still
mapped by hand: the service returns an `Order`, and the method ended in `order.toOrderDto()` or wrapped it in a
`Response`. The REST hop of the REST, service and entity story was therefore covered on one framework only.

Three facts of Quarkus REST 3.40 decide the shape of the answer. JAX-RS has no composed-annotation mechanism, so an
annotation cannot both route a method and name a target the way `@TelescopeGetMapping` does. Response filters run after
the return value, a `RestResponse` or the value a `Uni` completes with, has become a `Response`, and before the body is
written; `ContainerResponseContext.setEntity` drops the writer chosen at build time from the declared return type, and a
`GenericEntity` sets the type the next writer is chosen for. And Quarkus registers for reflection, in a native image,
the types it sees in resource signatures; a wire type that appears in none, such as an `OrderDto` named only in an
annotation, is not registered.

## Decision

```java
@GET
@Path("/{number}")
@TelescopeResponse(to = OrderDto.class)
public Order get(@PathParam("number") String number) {
  return desk.get(number);
}

@POST
@TelescopeResponse(to = OrderV2Dto.class)
public RestResponse<Order> place(@TelescopeRequestBody(from = PlaceOrderV2.class) @Valid PlaceOrder command) { ... }
```

1. **The annotations live in `telescope-quarkus`, beside `@GET`.** `@TelescopeResponse(to, using)` sits on a resource
   method and is a JAX-RS `@NameBinding`. `@TelescopeRequestBody(from, using, required)` sits on the body parameter, and
   a `TelescopeRequest<S, T>` parameter carries the body as read and as mapped. Names and attributes are the Spring
   starter's; the package is `io.github.eschizoid.telescope.quarkus.rest`.
2. **A response is mapped by a response filter bound to `@TelescopeResponse`.** The filter replaces the entity with a
   `GenericEntity` of the mapped value and its mapped type, keeping status and headers, so the JSON writer is chosen for
   the wire type and every Java caller receives what the signature promises. The filter runs on no other method. A
   response produced from an exception, by an exception mapper or by Quarkus REST, is written as it is, whatever the
   shape of its body, and so is a body of another type than the method returns.
3. **A request body is read by the reader Quarkus REST would use for the wire type, then mapped.** A
   `ServerMessageBodyReader`, registered ahead of every reader of the default priority, claims only the parameters the
   build found, keyed by the method id Quarkus REST gives each resource method. It asks the registered readers for the
   wire type, reads with the one that accepts it, and maps the value before the method runs. With `@Valid` on the
   parameter and `quarkus-hibernate-validator` present, it validates the value as read through the application's
   `Validator` and throws the exception Quarkus throws for an invalid REST parameter, so a violation is the usual `400`
   before anything is mapped. Each violation is named as Quarkus names a violation of a resource method's parameter,
   followed by the wire type's property, such as `place.command.buyer`. A missing required body is a `400`. Only a
   parameter of a method Quarkus REST routes is a body: a `TelescopeRequest` handed to a helper, a bean or a lambda is a
   plain value.
4. **Return shapes.** `S`, `List<S>`, `Collection<S>`, `Set<S>`, `Optional<S>`, a `RestResponse` of one of those, and a
   `Uni`, `CompletionStage` or `CompletableFuture` of any of these. A bare `Response` is refused, because it names no
   entity type to resolve a mapper for; the refusal names `RestResponse<S>`. `Multi<S>` is refused, because streamed
   elements are written without passing the response filters, and so is any generic type used raw, such as a raw
   `RestResponse`, which names no type for the values it holds. A request body is `T`, `List<T>`, `Collection<T>`,
   `Set<T>` or a `TelescopeRequest<S, T>`.
5. **The mapper is the one the Spring side finds.** `using`, then a `TelescopeProjection` bean for the pair, then a
   `Mapper` bean in `TelescopeMapperRegistry`, then `Telescope.mapperForward`; two candidates are refused unless `using`
   picks one. The order and its refusal messages live in `:internal`'s pairing spec as `EndpointMappers`,
   qualified-exported to both starters; each starter's lookup only asks its container for the candidates.
6. **`telescope-quarkus` is a Quarkus extension with a deployment artifact.** `telescope-quarkus-deployment` reads the
   annotations from the Jandex index at build time, classifies every shape, refuses a misconfigured method with the
   method named, registers the response filter and the body reader only when some method needs them, keeps the
   projection beans and the registry from being removed as unused, registers every wire type and both type arguments of
   each projection named by `using` for reflection, and records the bodies for the runtime. At startup the runtime finds
   each body's mapper, because the container's beans and the registry exist only once it runs, and fails the application
   with the method named. In dev and test mode the build steps run when the application boots, so every refusal is a
   boot failure there.
7. **The extension registers what telescope itself needs in a native image, in every application.** A Quarkus native
   image finds no `ServiceLoader` provider the build does not register, and telescope reads every service it has that
   way: the generated bridges `@Bridge` fields run on, the generated `@FromMap` binders, and telescope-jpa's container
   policy and proxy resolver. The build registers every provider of each, and as lambda capturing types the class that
   declares each `@Bridge` field, whose rows the mapper reads to check its bridge, and every projection and
   transformation the application declares, whose rows and paths are method references. Without them a `@Bridge` field
   whose class the image initializes at run time runs its runtime mapper there.
8. **With SmallRye OpenAPI present, a mapped body is documented as its wire type.** SmallRye OpenAPI builds a schema
   only for a type its annotation scanner reaches, it has no public API that builds one for a given type, and Quarkus
   gives an extension no way to add annotations to the index it scans. The deployment therefore generates, at build time
   and in memory, one resource class per mapped method, whose single method carries an `@APIResponse` and a
   `@RequestBody` with `@Schema(implementation = Wire.class)`, an array of it for a `List` or `Collection` body and an
   array of unique items for a `Set`, and the body's `required`. Each generated method has its own name and an operation
   id no Java method name can spell, so no operation id strategy makes two operations share one. An `OASFilter` it
   registers through `AddToOpenAPIDefinitionBuildItem` runs a second scan for each document through SmallRye's public
   `SmallRyeOpenAPI.builder()`. That scan reads the application's index with the generated classes added, but finds
   resources only among the generated classes, the classes that declare or route a mapped method, and the classes whose
   sub-resource locators lead to one. A class the application's own `mp.openapi.scan.*` include and exclude settings
   leave out, decided by SmallRye's rule for them, is left out of it too, along with its mapped methods' generated
   operations. The scan costs little, and a resource the application keeps out of its document is not scanned again.

   An `OperationHandler` marks every operation of that scan with the ids Quarkus REST keys its resource method by, so a
   mapped method's operation is found under each path and HTTP method SmallRye gives it: on a resource class, on an
   interface whose `@Path` it takes, or on a sub-resource under every locator that returns it. The scan resolves the
   context root as Quarkus resolves it for its own document, from `quarkus.http.root-path` and either `@ApplicationPath`
   or `quarkus.rest.path`, and reads the application's configuration without the keys that narrow what is scanned, the
   class and package filters and the operation profiles, so the two documents' paths agree and the generated operations
   are always read. Quarkus hands no public way to read the configuration a named document sees, so the scan reads it as
   the default document does; a schema setting only a named document makes is not applied to the wire schemas there.

   The filter then gives every success response of a matched operation, and its request body, the generated method's
   schema in each of their media types, and the request body its `required`, and brings in the component schemas the
   wire schemas name. Two scans number classes sharing a simple name in the order each meets them, so a component is
   matched to the document's by content as well as name. A document component named as SmallRye could have named the
   wire component's class, by its own name or that name followed by a duplicate number, describes the same thing when
   its content is equal once references are set aside and its references lead, position by position, to components that
   describe the same thing in turn; its name is used and nothing is added. Any other component keeps its name where the
   document has none of it, and otherwise takes its whole name followed by the next free number, as SmallRye numbers a
   duplicate, every reference among the copied schemas following it. Digits a class's own name ends in, as in `OrderV2`,
   are never taken for a duplicate number. A value shape that unwraps, `Optional`, `RestResponse`, `Uni`,
   `CompletionStage` or `CompletableFuture`, documents the wire type itself. A mapped method that names a schema for
   that body in its own `@APIResponse`, `@APIResponses`, `@APIResponseSchema`, `@RequestBody` or `@RequestBodySchema`
   keeps it. Only SmallRye's `io.smallrye.openapi.api` package and MicroProfile OpenAPI's API are used, and no private
   extension of either. The step that produces the build item runs only when the SmallRye OpenAPI extension consumes it,
   so an application without that extension builds and runs as before.

## Consequences

- A resource method names no mapper, and a native image needs no reflection configuration the application writes for the
  types its mapped bodies are read and written as. The build also registers each pair's generated bridge holder, which
  the forward mapping finds by name. A module of DTOs with no Jandex index needs indexing, because Quarkus walks a
  registered type's nested records through the index.
- Quarkus validates every `@Valid` parameter of a bean method, so the mapped value is validated too when its type
  carries constraints. A domain type usually carries none.
- The starter publishes two artifacts, and the runtime jar names the deployment artifact in
  `META-INF/quarkus-extension.properties`. An application still adds only `telescope-quarkus`. Inside this build, the
  Quarkus application plugin finds the deployment project through a `quarkusExtension` extension the runtime project
  declares itself, because the Quarkus extension plugin that would add it cannot be stored in the configuration cache.
- The tests boot Quarkus applications with `QuarkusUnitTest` in `:telescope-quarkus-tests`, a module that applies the
  Quarkus application plugin for the application model the harness needs; their coverage counts toward both the runtime
  and the deployment module. The demo's `OrderApiTest` covers the annotated endpoints end to end, and the native-image
  workflow makes the same calls on the demo built as a native executable. A pair with no projection or mapper bean maps
  through `Telescope.mapperForward`, as on the Spring side, and at startup each such body logs at DEBUG whether
  `ServiceLoader` finds a generated bridge's provider for the pair. The demo's shipping label is declared by a `@Bridge`
  field in a package outside `io.github.eschizoid.telescope`, which telescope-core's native settings initialize at build
  time, so the image initializes it at run time and the label names its recipient only through the provider; the native
  test checks the label and that log line. The demo indexes its domain module so the build can walk the records its DTOs
  hold, and registers the two records its refused-import trace reads through a runtime `fromMap` mapper, which is the
  application's own reflection.
- The element mapping is a `Function<Object, Object>`, the same erased step at the framework boundary as ADR-0020's.
- With SmallRye OpenAPI, the build scans the resources holding mapped methods a second time for each document, which
  costs build time in proportion to them and nothing at run time. A wire type is described from the index as every other
  type of the document is, so one in a module that carries no index needs it indexed.

## Alternatives considered

- **Resolving everything at startup in the runtime-only starter.** Rejected: discovery would read the resource methods'
  annotations and generic signatures by reflection, and every wire type would need `@RegisterForReflection` in the
  application before a native image could write it.
- **An index generated by the annotation processor.** It would carry the shapes as class literals and the reflection
  registration as `@RegisterForReflection`, without a deployment artifact. Rejected: it ties the feature to a processor
  the application may not run, and repeats what the Jandex index already holds.
- **A `MessageBodyWriter` or a `WriterInterceptor` for responses.** Rejected: both run once a writer has been chosen for
  the declared type, so the JSON writer would see the domain type's writer choice.
- **Validating the mapped value only.** Rejected, as in ADR-0021: constraints live on the wire type.
- **A Quarkus build item that adds annotations, or types to scan as schemas, to the OpenAPI index.** It would let the
  deployment annotate each mapped method in place and leave SmallRye one scan. No such item exists in Quarkus 3.40; the
  second scan is what stands in for it.
- **SmallRye's internal data-object scanner, called from a filter.** The smallest change, and one that breaks whenever
  the scanner's internals change. Rejected for the public builder.
- **Documenting the workaround alone:** MicroProfile OpenAPI's `@APIResponse` and `@RequestBody` on each mapped method.
  Rejected: it repeats on every endpoint the type the telescope annotation already names, and the filter writes the wire
  schema over the schema such an annotation names.
