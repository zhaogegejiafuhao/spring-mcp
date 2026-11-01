package com.acme.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByStatus(String status);

    @Query("select o from Order o where o.id > ?1")
    List<Order> findBiggerThan(Long id);
}
