# ADR-0023: A generated `Mappings` interface for calling a mapper from the value

**Status:** Accepted · **Date:** 2026-10-23

## Context

ADR-0022 declares a generated mapper as a `static final` field with `@Bridge`, usually on the source type itself, and
calls it through the field:

```java
public record Invoice(Long id, String customer, String currency, long totalCents) {
  @Bridge
  static final Mapper<Invoice, InvoiceDto> DTO = mapper(
    Invoice.class,
    InvoiceDto.class,
    to(Invoice::id, InvoiceDto::invoiceId)
  );
}

InvoiceDto dto = Invoice.DTO.forward(invoice);
```

Many users prefer to ask the value itself, as in `invoice.toInvoiceDto()`. Java has no extension methods, and an
annotation processor cannot add a method to a class it did not generate. A class can still implement an interface the
processor generates: javac resolves a type named in an `implements` clause once a processor has written it, and only
reports it missing if no round writes it. A default method on that interface can call the `@Bridge` field.

## Decision

1. **The processor generates one interface per source type that has `@Bridge` fields,** named `<Source>Mappings`, in the
   source type's package. A type that wants the call form names it in its `implements` clause. Implementing it is
   optional; the field form keeps working without it.
2. **Each `@Bridge` field whose source is that type adds one default method, `to<Target>()`,** named from the target's
   simple name, which returns `forward` of the field applied to `this`:

   ```java
   interface InvoiceMappings {
     default InvoiceDto toInvoiceDto() {
       return Invoice.DTO.forward((Invoice) this);
     }
   }

   InvoiceDto dto = invoice.toInvoiceDto();
   ```

   `@Bridge` gains no attribute for the method name. A different name is a hand-written one-line method on the type.

3. **A two-way mapper adds the reverse on the target side.** The processor also generates `<Target>Mappings` with a
   default method `to<Source>()` that returns `backward` of the field applied to `this`. A `mapperForward` field adds no
   reverse method.
4. **Two methods that would share a name on one interface are refused by name,** on the `@Bridge` field that adds the
   second. This happens when one source has mappers to two targets with the same simple name in different packages, or
   to the same target through two fields. The message names both fields and suggests the hand-written method.
5. **The default method costs what calling the field costs.** It reads a `static final` field and makes one call, which
   the JIT inlines; a paired benchmark against `Invoice.DTO.forward(invoice)` holds it to that.
6. **Without the processor the build fails on the missing interface.** A type that implements `<Source>Mappings` needs
   the generated interface to compile, so a build without the processor reports the missing type. That is the intended
   signal: the call form exists only where the processor runs, while the field form runs either way.
7. **Only types the user compiles can implement the interface.** A source type from a library keeps the field form.

## Consequences

- A value converts itself with `invoice.toInvoiceDto()` and back with `dto.toInvoice()`, with no lookup per call.
- The user names one generated type, in the `implements` clause, and an IDE shows it as missing until the first build
  runs the processor. The field form stays the default in the documentation; this is the optional call form.
- The method name follows the target's name, so renaming the target type renames the method on the next build.

## Alternatives considered

- **A default method that finds the mapper by class pair, as in `invoice.to(InvoiceDto.class)`.** Rejected: it looks the
  mapper up on every call and makes one shared call site for every pair, which costs more than calling the field.
- **An attribute on `@Bridge` naming the method.** Deferred: the derived name covers the common case, and the
  hand-written one-line method covers the rest without new API.
- **Adding the method to the source type by rewriting its syntax tree.** Rejected for the reason ADR-0022 gives: it is
  outside JSR-269 and is what makes Lombok fragile.
