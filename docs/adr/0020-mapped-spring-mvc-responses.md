# ADR-0020: Mapped Spring MVC responses through composed mapping annotations

**Status:** Accepted · **Date:** 2026-10-11

## Context

A REST adapter usually ends each handler the same way: the service returns one type, a mapper turns it into the response
type, and the handler returns that. The mapper call and its injected field are the only lines that differ from one
endpoint to the next, and a telescope user still writes both.

Java decides what can remove them. A method declared to return `UserRestDto` cannot return the `UserDto` its service
produced: `javac` rejects the `return`, and no annotation processor changes that without rewriting the compiler's tree,
which leaves an error on every such endpoint in the IDE. A proxy around the handler could swap the returned value, but a
caller through the proxy would then receive a `UserRestDto` where the signature promised a `UserDto`, and a call from
inside the class, which skips the proxy, would receive the `UserDto`.

## Decision

The handler declares the type it returns, and the annotation names the response type.

```java
@TelescopeGetMapping(value = "/api/v1/users/{id}", to = UserRestDto.class)
UserDto user(@PathVariable String id) {
  return service.findUser(id);
}
```

1. **The mapping happens where Spring MVC writes the response body, never in the Java call.** A `BeanPostProcessor`
   wraps the adapter's body-writing return-value handlers. The value is mapped, and the declared type swapped, before a
   message converter is chosen, so content negotiation, the JSON converter and `ResponseBodyAdvice` see the response
   type as if the method had declared it. Every Java caller receives what the signature promises.
2. **Composed annotations carry the route and the target.** `@TelescopeGetMapping`, `@TelescopePostMapping`,
   `@TelescopePutMapping`, `@TelescopePatchMapping` and `@TelescopeDeleteMapping` live in
   `telescope-spring-boot-starter`. Each is meta-annotated with `@RequestMapping(method = ...)`, since Spring's
   `@GetMapping` family can only sit on a method, and with `@TelescopeResponse`, which can only sit on an annotation and
   so never on a method that writes no response.
3. **Every endpoint's mapping is resolved once, at startup.** The lookup takes a `@TelescopeMapper` projection for the
   pair, then a `Mapper` bean, then `Telescope.mapperForward` (a generated `@Bridge`, or the same-name forward mapping).
   Two candidates, an unsupported return shape, or a handler that writes a view name fail startup with the method named;
   `using` picks a projection and supplies the target.
4. **A fixed set of return shapes.** `S`, `List<S>`, `Collection<S>`, `Set<S>`, `Optional<S>`, `Page<S>`, and a
   `ResponseEntity` of one of those. A value of another shape at run time, such as the `ProblemDetail` body that
   `ResponseEntity.of(problem).build()` produces for any `T`, is written unmapped with its declared type.

## Consequences

- An endpoint names no mapper. A DTO whose components match the source by name needs no mapper anywhere.
- The response types are documented: with springdoc on the classpath, a global customizer rewrites the success responses
  of every document, the default one and each `GroupedOpenApi` group, and registers in each only the schemas it
  references.
- The feature covers Spring MVC only. Messaging listeners, async and reactive return types, request bodies and Quarkus
  are not covered; each would reuse the resolution rules with a hook of its own.
- Code that looks the body processors up by type in the adapter's handler list finds the wrapper instead.
- The element mapping is a `Function<Object, Object>`. The starter cannot see `:internal`, projections expose only
  `map`, and `ResponseEntity` and `Page` are web types the lattice does not model; this is the one erased step, at the
  Spring boundary.

## Alternatives considered

- **Rewriting the compiler's tree** so the handler can declare `UserRestDto` and return a `UserDto`. Rejected: every IDE
  reports the `return` as a type error, and it depends on unsupported `javac` internals.
- **A proxy around the handler.** Rejected: callers through the proxy and calls from inside the class receive different
  types, and neither matches what the compiler checked.
- **A `ResponseBodyAdvice`.** Rejected: it runs after Spring has chosen the converter and its target type from the
  declared return type.
- **A generated facade interface over the service.** Every adapter would get the mapped type through a plain Java call,
  at the cost of one interface method per endpoint. Kept open for adapters that have no return value to hook, such as
  gRPC.

## Open questions

- Messaging listeners accept composed annotations too; `@KafkaListener` and its siblings are the natural next adapter.
- Request bodies: the same idea in the other direction, mapping an incoming DTO before the handler runs.
