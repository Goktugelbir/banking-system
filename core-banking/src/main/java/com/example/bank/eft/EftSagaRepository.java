package com.example.bank.eft;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EftSagaRepository extends JpaRepository<EftSaga, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EftSaga s where s.id = :id")
    Optional<EftSaga> findByIdForUpdate(@Param("id") UUID id);

    @Query("select s.id from EftSaga s where s.status in :statuses and s.updatedAt < :before order by s.updatedAt")
    List<UUID> findStuck(@Param("statuses") Collection<EftSaga.Status> statuses, @Param("before") Instant before);
}
