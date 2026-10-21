package au.org.resourcework.waste_tracking_api.reference;

import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ContainerTypeRepository extends JpaRepository<ContainerType, Long> {
    List<ContainerType> findByActiveTrueOrderByIdAsc();

    @Query(value = """
        SELECT c.* FROM container_type c JOIN container_type_applicability a ON a.container_type_id = c.id
        WHERE a.context = :context AND a.active AND c.active ORDER BY c.id
        """, nativeQuery = true)
    List<ContainerType> findAvailable(@Param("context") String context);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM container_type_applicability WHERE container_type_id = :id AND context = :context)", nativeQuery = true)
    boolean belongsToContext(@Param("id") Long id, @Param("context") String context);

    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM container_type c JOIN container_type_applicability a ON a.container_type_id = c.id
        WHERE c.id = :id AND a.context = :context AND a.active AND c.active)
        """, nativeQuery = true)
    boolean isAvailable(@Param("id") Long id, @Param("context") String context);
}
