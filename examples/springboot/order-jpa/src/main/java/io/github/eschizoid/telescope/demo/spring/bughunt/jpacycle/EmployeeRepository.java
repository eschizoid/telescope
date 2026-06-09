package io.github.eschizoid.telescope.demo.spring.bughunt.jpacycle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Plain Spring Data JPA repository over {@link EmployeeEntity}; auto-discovered by component scan.
 */
@Repository
public interface EmployeeRepository extends JpaRepository<EmployeeEntity, Long> {}
