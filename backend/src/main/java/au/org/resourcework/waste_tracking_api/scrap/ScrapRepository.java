package au.org.resourcework.waste_tracking_api.scrap;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ScrapRepository extends JpaRepository<ScrapRecord, Long> {
    Optional<ScrapRecord> findBySubmissionId(UUID id);
    Optional<ScrapRecord> findByIdAndDeletedAtIsNull(Long id);
    List<ScrapRecord> findByDeletedAtIsNullAndReportingMonthBetweenOrderByReportingMonthDescIdDesc(LocalDate from, LocalDate to);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ScrapRecord r where r.id=:id and r.deletedAt is null")
    Optional<ScrapRecord> lockActiveById(@Param("id") Long id);
    @Modifying
    @Query(value="""
        insert into scrap_record(submission_id,reporting_month,iron_and_steel_weight,aluminium_weight,other_non_ferrous_weight,mixed_metals_weight,ewaste_weight,created_by,updated_by,created_at,updated_at)
        values (:submissionId,:month,:ironAndSteelWeight,:aluminiumWeight,:otherNonFerrousWeight,:mixedMetalsWeight,:ewasteWeight,:actor,:actor,:now,:now)
        on conflict(submission_id) do nothing
        """,nativeQuery=true)
    int insertIfAbsent(@Param("submissionId") UUID submissionId, @Param("month") LocalDate month,
        @Param("ironAndSteelWeight") BigDecimal ironAndSteelWeight,
        @Param("aluminiumWeight") BigDecimal aluminiumWeight,
        @Param("otherNonFerrousWeight") BigDecimal otherNonFerrousWeight,
        @Param("mixedMetalsWeight") BigDecimal mixedMetalsWeight,
        @Param("ewasteWeight") BigDecimal ewasteWeight, @Param("actor") Long actor, @Param("now") Instant now);
    interface Totals {
        BigDecimal getIronAndSteelWeight();
        BigDecimal getAluminiumWeight();
        BigDecimal getOtherNonFerrousWeight();
        BigDecimal getMixedMetalsWeight();
        BigDecimal getEwasteWeight();
    }
    @Query(value="""
        select coalesce(sum(iron_and_steel_weight),0) as "ironAndSteelWeight",coalesce(sum(aluminium_weight),0) as "aluminiumWeight",coalesce(sum(other_non_ferrous_weight),0) as "otherNonFerrousWeight",coalesce(sum(mixed_metals_weight),0) as "mixedMetalsWeight",coalesce(sum(ewaste_weight),0) as "ewasteWeight" from scrap_record where deleted_at is null and reporting_month=:month and (cast(:excluded as bigint) is null or id<>:excluded)
        """,nativeQuery=true)
    Totals totals(@Param("month") LocalDate month, @Param("excluded") Long excluded);
}
