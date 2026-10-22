package au.org.resourcework.waste_tracking_api.electricaltesting;
import java.time.*;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ElectricalTestingTallyRepository extends JpaRepository<ElectricalTestingTally,Long> {
    Optional<ElectricalTestingTally> findBySubmissionId(UUID submissionId);
    Optional<ElectricalTestingTally> findByIdAndDeletedAtIsNull(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ElectricalTestingTally r where r.id=:id and r.deletedAt is null")
    Optional<ElectricalTestingTally> lockActiveById(@Param("id") Long id);
    @Query("select r from ElectricalTestingTally r where r.deletedAt is null and r.recordDate >= :from and r.recordDate <= :to order by r.recordDate desc,r.id desc")
    List<ElectricalTestingTally> findActiveBetween(@Param("from") LocalDate from,@Param("to") LocalDate to);
    interface Total { Long getCategoryId(); TallyType getTallyType(); Long getCount(); }
    @Query("select c.id as categoryId,r.tallyType as tallyType,sum(r.count) as count from ElectricalTestingTally r left join r.category c where r.deletedAt is null and r.recordDate >= :start and r.recordDate < :end group by c.id,r.tallyType")
    List<Total> totals(@Param("start") LocalDate start,@Param("end") LocalDate end);

    @Modifying
    @Query(value="""
        INSERT INTO electrical_testing_tally (submission_id,record_date,category_id,tally_type,count,created_by,created_at,updated_by,updated_at)
        VALUES (:uuid,:date,:category,:type,:count,:actor,:now,:actor,:now) ON CONFLICT (submission_id) DO NOTHING
        """,nativeQuery=true)
    int insertIfAbsent(@Param("uuid") UUID uuid,@Param("date") LocalDate date,@Param("category") Long category,
            @Param("type") String type, @Param("count") Integer count,@Param("actor") Long actor,@Param("now") Instant now);
}
