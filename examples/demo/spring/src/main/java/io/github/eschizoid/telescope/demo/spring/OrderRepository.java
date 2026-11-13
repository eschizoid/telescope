package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.OrderStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OrderRepository extends JpaRepository<Order, Long> {
  Optional<Order> findByNumber(String number);

  List<Order> findAllByOrderByNumber();

  /** A page of the orders in one status, with each order's customer joined into the same query. */
  @EntityGraph(attributePaths = "customer")
  Page<Order> findByStatus(OrderStatus status, Pageable pageable);

  /**
   * Loads the tags of {@code orders} in one query. The orders are the instances the session already
   * holds, so their tag lists are filled in place.
   */
  @Query("select distinct o from Order o left join fetch o.tags where o in :orders")
  List<Order> loadTags(Collection<Order> orders);
}
