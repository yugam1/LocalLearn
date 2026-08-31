package com.ecommerce.orderservice.repository;

import com.ecommerce.orderservice.model.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByName(String name);

    /**
     * Pessimistic write lock — SELECT ... FOR UPDATE. Row stays locked until
     * the enclosing transaction commits/rolls back; concurrent callers block
     * or time out. See docs/phase1_task5.md, Pessimistic Locking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.name = :name")
    Optional<Product> findByNameForUpdate(@Param("name") String name);
}
