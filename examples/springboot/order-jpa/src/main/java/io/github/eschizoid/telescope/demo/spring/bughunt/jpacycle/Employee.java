package io.github.eschizoid.telescope.demo.spring.bughunt.jpacycle;

import java.util.List;
import java.util.Optional;

/**
 * Self-referencing domain record — an Employee may have a manager (also an Employee) and a list of
 * reports (each also an Employee). The record is symmetrical with {@link EmployeeEntity} so the
 * deep-mapping engine has same-name pairs both directions.
 *
 * <p>Intentionally <b>NOT</b> annotated with {@code @Focus} — this bughunt slice only exercises the
 * runtime mapper path (where {@code DeepMap}'s {@link io.github.eschizoid.telescope.mapping.DeepMap
 * TypePair} cycle cache is the load-bearing defence). Adding {@code @Focus} would emit a codegen
 * Path navigator + holder constants that this slice doesn't need.
 *
 * <p>This is the slice's reason for existing: prove (or disprove) that telescope's
 * <em>type-level</em> cycle detection survives a real Hibernate-managed value graph where every
 * node's {@code manager} points up and every node's {@code reports} point back down — i.e., {@code
 * bob.manager == alice && alice.reports.contains(bob)} is a literal value cycle, not just a type
 * cycle.
 */
public record Employee(Long id, String name, Optional<Employee> manager, List<Employee> reports) {}
