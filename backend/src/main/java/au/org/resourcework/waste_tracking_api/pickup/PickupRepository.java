package au.org.resourcework.waste_tracking_api.pickup;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PickupRepository extends JpaRepository<PickupRecord, Long> {
    Optional<PickupRecord> findBySubmissionId(UUID submissionId);
    Optional<PickupRecord> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PickupRecord r where r.id = :id and r.deletedAt is null")
    Optional<PickupRecord> lockActiveById(@Param("id") Long id);

    @Query("select r from PickupRecord r where r.deletedAt is null and r.recordDate >= :from and r.recordDate <= :to order by r.recordDate desc, r.id desc")
    List<PickupRecord> findActiveBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Modifying
    @Query(value = """
        INSERT INTO pickup_record
        (submission_id, record_date, pickup_location, net_weight, created_by, created_at, updated_by, updated_at)
        VALUES (:submissionId, :date, :location, :netWeight, :actor, :now, :actor, :now)
        ON CONFLICT (submission_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("submissionId") UUID submissionId, @Param("date") LocalDate date,
            @Param("location") String location, @Param("netWeight") BigDecimal netWeight,
            @Param("actor") Long actor, @Param("now") Instant now);
}
