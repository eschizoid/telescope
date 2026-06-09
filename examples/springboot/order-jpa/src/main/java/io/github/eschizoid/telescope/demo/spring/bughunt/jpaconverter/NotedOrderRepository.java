package io.github.eschizoid.telescope.demo.spring.bughunt.jpaconverter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link NotedOrderEntity}. */
@Repository
public interface NotedOrderRepository extends JpaRepository<NotedOrderEntity, Long> {}
