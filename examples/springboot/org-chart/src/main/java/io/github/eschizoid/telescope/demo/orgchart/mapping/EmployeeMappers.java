package io.github.eschizoid.telescope.demo.orgchart.mapping;

import static io.github.eschizoid.telescope.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Mapper;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.orgchart.domain.Employee;
import io.github.eschizoid.telescope.demo.orgchart.persistence.EmployeeEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires a single {@code Mapper<Employee, EmployeeEntity>} via {@link Telescope#mapper(Class, Class,
 * io.github.eschizoid.telescope.MapStep...)}. All fields are same-name + same-shape (id, name,
 * manager, reports) — so this is a pure auto-inference test of {@link
 * io.github.eschizoid.telescope.DeepMap}'s TypePair cycle cache against a self-referencing pair
 * {@code (Employee, EmployeeEntity)}.
 */
@Configuration
public class EmployeeMappers {

  @Bean
  public Mapper<Employee, EmployeeEntity> employeeMapper() {
    // No explicit rows needed — every field has a same-name same-shape twin on the other side.
    // The cycle is purely type-level: Employee references Optional<Employee> + List<Employee>,
    // which feeds back into the (Employee, EmployeeEntity) TypePair the cache reserved on entry.
    return Telescope.mapper(Employee.class, EmployeeEntity.class, writeBeans(SETTERS));
  }
}
