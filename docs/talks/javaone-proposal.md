# Talk proposal: optics for Java developers

A conference session proposal about optics in plain Java, with telescope as the worked example. Each section below
matches one field of the submission form, and the character counts are the form's limits.

## Title (75 characters)

Optics for Java Developers: Lenses and Prisms over Records and Sealed Types

## Abstract (1000 characters)

Records made immutable data easy to declare and hard to change: updating one field three levels down means rebuilding
every record on the way. Optics, an idea from functional programming, solve this by turning a path into a value you can
read through, write through and compose. In this session I build them in plain Java 25. A lens focuses on a record
component, a prism on one case of a sealed interface, and a traversal on every element of a collection, and composing
them gives one typed path through a whole object graph. Method references name each step, so the compiler checks every
path and an IDE rename carries through. Then I show what happens when the idea meets production: telescope, an open
source library built on these optics, uses one path both to update data and to map between types, with a runtime engine
and a code generator held to the same answers. I close with benchmarks against MapStruct, taken with control rows,
including the results that do not favour optics.

## Audience

- **Level:** intermediate (4 to 6 years of Java experience).
- **Roles:** cloud and backend developers, framework and library developers and maintainers, architects.
- **Primary track:** Core Java.
- **Secondary track:** Cloud, Enterprise Java and Data.

## Elevator pitch (2000 characters)

Most Java developers have never met optics, and they solve a problem every one of them has. Records and sealed
interfaces gave Java good immutable data, but changing a value deep inside it still means rebuilding each record by hand
on the way down. Optics turn that path into a first-class value. Attendees leave with three things. First, how to build
a lens, a prism and a traversal in plain Java 25 using records, sealed interfaces, pattern matching and method
references, and how they compose into one typed path. Second, where the abstraction stays out of the way: the people who
use the path never need to know the words lens or prism. Third, what it costs. I use telescope, a library I wrote and
maintain, as the case study: one path both updates data and maps between types, a runtime engine and a code generator
are held to the same answers by a shared rule set and a test corpus, and measured numbers taken in CI with control rows
show where generated code matches MapStruct and where the runtime path costs more. The live demo starts with a deep
record update written by hand, replaces it with a composed path, and ends by mapping between two sealed hierarchies.

## Additional notes (2000 characters)

The session is about a technique attendees can use in plain Java, with telescope as the worked example in the last
third. telescope is open source (Apache 2.0) on GitHub at github.com/eschizoid/telescope and published to Maven Central.
I wrote a three-part blog series on its design at mariano-gonzalez.com. I am happy to adapt the session length or turn
it into a hands-on lab.

## Speaker bio (2000 characters)

Mariano Gonzalez is a Distinguished Engineer at Capital One, where he leads the design and development of the company's
internal Java libraries and frameworks, and its wider JVM ecosystem. His team builds the shared pieces that every Java
service needs, so that product teams can rely on one well-maintained version instead of each writing their own.

Before Capital One, he worked at several startups in the Chicago area, building backend systems in small teams where the
same engineer designs a service, ships it and keeps it running.

Originally from Mexico, he has lived in Chicago for the last 15 years.

Outside work, he maintains two open source Java libraries: telescope, which brings optics and typed object mapping to
modern Java, and kpipe, a composable Kafka consumer library for building modular, testable data pipelines on the JVM. He
writes about their design at mariano-gonzalez.com.

A shorter version, where a form asks for one:

Mariano Gonzalez is a Distinguished Engineer at Capital One, where he leads the company's internal Java libraries,
frameworks and JVM ecosystem. Before that he worked at several Chicago startups. Originally from Mexico, he has lived in
Chicago for 15 years. He maintains two open source Java libraries, telescope (optics and object mapping) and kpipe
(Kafka consumer pipelines).

## Session outline

The split gives most of the time to the technique, so the session stays useful to someone who never adopts the library.

1. **The problem (about 10%).** A deep update of nested records written by hand, and why `with`-style copies do not
   compose.
2. **Optics in plain Java 25 (about 50%).** A lens over a record component, a prism over one case of a sealed interface,
   a traversal over a collection, and how the three compose. Method references as the names of the steps. Hiding the
   vocabulary behind one fluent type.
3. **Telescope as the case study (about 25%).** The same paths used for updates and for mapping between types, the
   runtime engine and the code generator, and the shared rule set and test corpus that keep the two in agreement.
4. **What it costs (about 15%).** Benchmarks against MapStruct with their control rows, and the cases where the runtime
   path is slower.

## Notes for the talk itself

- MapStruct appears once, as the familiar comparison, not as an opponent.
- Benchmark figures on the slides come from a run of the `Benchmarks` workflow with its control rows, not from a laptop.
  Allocation figures are the durable evidence; timing ratios are quoted from one run.
- The sealed-hierarchy demo needs runtime dispatch between sealed roots, which must be in a release before the talk.
- The claim about renames is the IDE carrying a rename through method references. MapStruct already fails at compile
  time on an unknown property name, so the talk does not claim the build breaks where MapStruct's would not.
