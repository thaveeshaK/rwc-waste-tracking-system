package au.org.resourcework.waste_tracking_api.ewastepickup;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EwastePickupRepository extends JpaRepository<EwastePickupRecord, Long> {
    Optional<EwastePickupRecord> findBySubmissionId(UUID submissionId);
    Optional<EwastePickupRecord> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EwastePickupRecord r where r.id = :id and r.deletedAt is null")
    Optional<EwastePickupRecord> lockActiveById(@Param("id") Long id);

    @Query("select r from EwastePickupRecord r where r.deletedAt is null and r.recordDate >= :from and r.recordDate <= :to order by r.recordDate desc, r.id desc")
    List<EwastePickupRecord> findActiveBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Modifying
    @Query(value = """
        INSERT INTO ewaste_pickup_record
        (submission_id, record_date, organisation, entered_weight, total_tare_weight, net_weight, created_by, created_at, updated_by, updated_at)
        VALUES (:submissionId, :date, :location, :enteredWeight, :tare, :netWeight, :actor, :now, :actor, :now)
        ON CONFLICT (submission_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("submissionId") UUID submissionId, @Param("date") LocalDate date,
            @Param("location") String location, @Param("enteredWeight") BigDecimal enteredWeight, @Param("tare") BigDecimal tare, @Param("netWeight") BigDecimal netWeight,
            @Param("actor") Long actor, @Param("now") Instant now);
    @Query(value="select distinct organisation from ewaste_pickup_record where deleted_at is null and position(lower(:query) in lower(organisation))>0 order by organisation limit 20",nativeQuery=true)
    List<String> organisations(@Param("query") String query);
}
