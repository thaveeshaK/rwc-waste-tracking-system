package au.org.resourcework.waste_tracking_api.salvage;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SalvageRepository extends JpaRepository<SalvageRecord, Long> {
    Optional<SalvageRecord> findBySubmissionId(UUID submissionId);
    Optional<SalvageRecord> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SalvageRecord r where r.id = :id and r.deletedAt is null")
    Optional<SalvageRecord> lockActiveById(@Param("id") Long id);

    @Query("select r from SalvageRecord r where r.deletedAt is null and r.recordDate >= :from and r.recordDate <= :to order by r.recordDate desc, r.id desc")
    List<SalvageRecord> findActiveBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Modifying
    @Query(value = """
        INSERT INTO salvage_record
        (submission_id, record_date, crew, time_up, kg_up, time_down, kg_down, created_by, created_at, updated_by, updated_at)
        VALUES (:submissionId, :date, :crew, :timeUp, :kgUp, :timeDown, :kgDown, :actor, :now, :actor, :now)
        ON CONFLICT (submission_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("submissionId") UUID submissionId, @Param("date") LocalDate date,
            @Param("crew") String crew, @Param("timeUp") LocalTime timeUp, @Param("kgUp") BigDecimal kgUp,
            @Param("timeDown") LocalTime timeDown, @Param("kgDown") BigDecimal kgDown,
            @Param("actor") Long actor, @Param("now") Instant now);
}
