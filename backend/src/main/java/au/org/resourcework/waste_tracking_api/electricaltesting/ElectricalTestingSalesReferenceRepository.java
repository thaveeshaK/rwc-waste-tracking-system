package au.org.resourcework.waste_tracking_api.electricaltesting;
import java.time.*;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ElectricalTestingSalesReferenceRepository extends JpaRepository<ElectricalTestingSalesReference,Long> {
    Optional<ElectricalTestingSalesReference> findBySubmissionId(UUID submissionId);
    Optional<ElectricalTestingSalesReference> findByIdAndDeletedAtIsNull(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ElectricalTestingSalesReference r where r.id=:id and r.deletedAt is null")
    Optional<ElectricalTestingSalesReference> lockActiveById(@Param("id") Long id);
    List<ElectricalTestingSalesReference> findByReportingMonthAndDeletedAtIsNullOrderByCategoryDisplayOrderAsc(LocalDate month);

    @Modifying
    @Query(value="""
        INSERT INTO electrical_testing_sales_reference (submission_id,reporting_month,category_id,sold_last_month_count,created_by,created_at,updated_by,updated_at)
        VALUES (:uuid,:date,:category,:count,:actor,:now,:actor,:now) ON CONFLICT DO NOTHING
        """,nativeQuery=true)
    int insertIfAbsent(@Param("uuid") UUID uuid,@Param("date") LocalDate date,@Param("category") Long category,
             @Param("count") Integer count,@Param("actor") Long actor,@Param("now") Instant now);
}
