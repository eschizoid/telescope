package io.github.eschizoid.telescope.demo.spring.bughunt.lazyfetch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for the LAZY-fetch slice. Sibling of {@code OrderRepository}. */
@Repository
public interface LazyDocumentRepository extends JpaRepository<LazyDocumentEntity, Long> {}
