package au.org.resourcework.waste_tracking_api.cratepallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CratePalletRepository extends JpaRepository<CratePalletRecord, Long> {
    Optional<CratePalletRecord> findBySubmissionId(UUID submissionId);
    Optional<CratePalletRecord> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CratePalletRecord r where r.id = :id and r.deletedAt is null")
    Optional<CratePalletRecord> lockActiveById(@Param("id") Long id);

    @Query("""
        select r from CratePalletRecord r where r.deletedAt is null
        and r.recordDate >= :from and r.recordDate <= :to
        order by r.recordDate desc, r.id desc
        """)
    List<CratePalletRecord> findActiveBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // A database uniqueness constraint resolves concurrent retries without aborting the transaction.
    @Modifying
    @Query(value = """
        INSERT INTO crate_pallet_record
        (submission_id, record_date, container_type_id, gross_weight, tare_weight, net_weight,
         created_by, created_at, updated_by, updated_at)
        VALUES (:submissionId, :date, :containerId, :gross, :tare, :net, :actor, :now, :actor, :now)
        ON CONFLICT (submission_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("submissionId") UUID submissionId, @Param("date") LocalDate date,
            @Param("containerId") Long containerId, @Param("gross") BigDecimal gross,
            @Param("tare") BigDecimal tare, @Param("net") BigDecimal net,
            @Param("actor") Long actor, @Param("now") Instant now);
}
