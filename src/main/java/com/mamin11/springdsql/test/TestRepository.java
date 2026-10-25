package com.mamin11.springdsql.test;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface TestRepository extends JpaRepository<TestEntity, UUID> {

    List<TestEntity> findByNameNotOrderByIdAsc(String name, Pageable pageable);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            update test set name = :name
            where id in (
                select id from test
                where name <> :name
                order by id
                limit :batchSize
            )
            """, nativeQuery = true)
    int renameNextBatch(@Param("name") String name, @Param("batchSize") int batchSize);
}
