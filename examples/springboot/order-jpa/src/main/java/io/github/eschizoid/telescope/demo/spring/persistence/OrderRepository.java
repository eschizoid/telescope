package io.github.eschizoid.telescope.demo.spring.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository — Spring generates the implementation at startup. Both controllers
 * (runtime + codegen) call the same repository, so the only thing varying across the two flows is
 * the telescope mapper that bridges {@code Order} (record) and {@link OrderEntity} (bean).
 */
@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {}
