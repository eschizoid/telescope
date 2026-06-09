package io.github.eschizoid.telescope.demo.orgchart.domain;

import java.util.List;
import java.util.Optional;

/**
 * Self-referencing domain record — an Employee may have a manager (also an Employee) and a list of
 * reports (each also an Employee). The record is symmetrical with {@code
 * persistence.EmployeeEntity} so the deep-mapping engine has same-name pairs both directions.
 *
 * <p>Intentionally <b>NOT</b> annotated with {@code @Focus} — this submodule only exercises the
 * runtime mapper path, where DeepMap's TypePair cycle cache is the load-bearing defence. Adding
 * {@code @Focus} would emit a codegen Path navigator + holder constants this demo doesn't need.
 *
 * <p>The submodule's reason for existing: prove that telescope handles a real Hibernate-managed
 * self-referencing entity graph where every node's {@code manager} points up and every node's
 * {@code reports} point back down — i.e., {@code bob.manager == alice &&
 * alice.reports.contains(bob)} is a literal value cycle, not just a type cycle. DeepMap's
 * per-traversal IdentityHashMap seen-set severs the value cycle on re-entry.
 */
public record Employee(Long id, String name, Optional<Employee> manager, List<Employee> reports) {}
