package au.org.resourcework.waste_tracking_api.whitegoods;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface WhitegoodsRepository extends JpaRepository<WhitegoodsRecord, Long> {
    Optional<WhitegoodsRecord> findBySubmissionId(UUID submissionId);
    Optional<WhitegoodsRecord> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WhitegoodsRecord r where r.id = :id and r.deletedAt is null")
    Optional<WhitegoodsRecord> lockActiveById(@Param("id") Long id);

    @Query("""
        select r from WhitegoodsRecord r where r.deletedAt is null
        and r.recordDate >= :from and r.recordDate <= :to
        order by r.recordDate desc, r.id desc
        """)
    List<WhitegoodsRecord> findActiveBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // A database uniqueness constraint resolves concurrent retries without aborting the transaction.
    @Modifying
    @Query(value = """
        INSERT INTO whitegoods_record
        (submission_id, record_date, whitegoods_count, kg_per_item, estimated_weight,
         created_by, created_at, updated_by, updated_at)
        VALUES (:submissionId, :date, :count, :factor, :weight, :actor, :now, :actor, :now)
        ON CONFLICT (submission_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("submissionId") UUID submissionId, @Param("date") LocalDate date,
            @Param("count") Integer count,
            @Param("factor") BigDecimal factor, @Param("weight") BigDecimal weight,
            @Param("actor") Long actor, @Param("now") Instant now);

    @Query("select coalesce(sum(r.count), 0) from WhitegoodsRecord r where r.deletedAt is null and r.recordDate >= :start and r.recordDate < :end")
    Long sumActiveCount(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
