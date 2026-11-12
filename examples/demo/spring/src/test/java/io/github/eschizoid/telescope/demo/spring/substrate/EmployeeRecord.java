package io.github.eschizoid.telescope.demo.spring.substrate;

import java.util.List;
import java.util.Optional;

/** An employee as a value, with an optional manager and a list of reports of its own type. */
public record EmployeeRecord(Long id, String name, Optional<EmployeeRecord> manager, List<EmployeeRecord> reports) {}
