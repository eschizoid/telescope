package io.github.eschizoid.telescope.demo.orgchart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the org-chart demo. Single self-referencing {@code Mapper<Employee,
 * EmployeeEntity>} bean against a real Hibernate graph proves telescope's cycle handling at both
 * layers — type-level at mapper construction, value-level at {@code mapper.backward(...)}.
 */
@SpringBootApplication
public class DemoApplication {

  static void main(final String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
