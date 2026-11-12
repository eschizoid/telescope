package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.order.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<Order, Long> {
  Optional<Order> findByNumber(String number);

  List<Order> findAllByOrderByNumber();
}
