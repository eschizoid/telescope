# Talk proposal: typed object mapping in modern Java

A conference session proposal built around telescope. Each section below matches one field of the submission form, and
the character counts are the form's limits.

## Title (75 characters)

Typed Object Mapping in Modern Java: Records, Sealed Types and Optics

## Abstract (1000 characters)

Most Java services copy data between entities, records and DTOs, and most teams use MapStruct for it, where the field
names that drive a mapping live in strings inside annotations. I built telescope, an open source mapper for Java 25, to
find out how far records, sealed interfaces and method references can replace those strings. Every path is a method
reference, so an IDE rename carries through every mapping. One mapping runs two ways: at runtime with no build step, or
generated at compile time for hot loops. A shared rule set and a cross-path test corpus fail the build when the two
disagree. In this session I walk through the design: an internal optics lattice (lens, prism, traversal), case-by-case
dispatch over sealed hierarchies, LambdaMetafactory instead of reflection on the hot path, and native-image support. I
show the benchmarks against MapStruct, including the ones that do not favour telescope, and what it took to make two
engines give the same answer.

## Audience

- **Level:** intermediate (4 to 6 years of Java experience).
- **Roles:** cloud and backend developers, framework and library developers and maintainers, architects.
- **Primary track:** Core Java.
- **Secondary track:** Cloud, Enterprise Java and Data.

## Elevator pitch (2000 characters)

Mapping one object to another is routine work in almost every Java service, and the usual tool, MapStruct, describes it
with field names in strings. This talk shows what the language now gives us to do better: records for the data, sealed
interfaces for the variants, and method references for the paths. I use telescope, a library I wrote and maintain, as
the worked example. Attendees leave with three things they can use in their own code. First, a way to model navigation
over immutable data (lenses, prisms and traversals) without exposing any of that vocabulary to users. Second, a pattern
for keeping a runtime path and a code-generated path in agreement, using one shared rule set and a test corpus that
fails the build on any difference. Third, measured numbers, taken in CI with control rows, on where generated code
matches MapStruct and where the runtime path costs more. The demo maps between two sealed hierarchies on both paths and
shows the compiler refusing a wrong mapping with the same message the runtime gives.

## Additional notes (2000 characters)

telescope is open source (Apache 2.0) on GitHub at github.com/eschizoid/telescope and published to Maven Central. I
wrote a three-part blog series on its design at mariano-gonzalez.com. I am happy to adapt the session length or turn it
into a hands-on lab.

## Notes for the talk itself

- Benchmark figures on the slides come from a run of the `Benchmarks` workflow with its control rows, not from a laptop.
  Allocation figures are the durable evidence; timing ratios are quoted from one run.
- The sealed-hierarchy demo needs runtime dispatch between sealed roots, which must be in a release before the talk.
- The claim about renames is the IDE carrying a rename through method references. MapStruct already fails at compile
  time on an unknown property name, so the talk does not claim the build breaks where MapStruct's would not.
