package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.order.Customer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerRepository extends JpaRepository<Customer, Long> {
  Optional<Customer> findByEmail(String email);
}
