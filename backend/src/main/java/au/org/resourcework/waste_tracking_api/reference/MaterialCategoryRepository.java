package au.org.resourcework.waste_tracking_api.reference;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialCategoryRepository extends JpaRepository<MaterialCategory, Long> {
    List<MaterialCategory> findByActiveTrueOrderByIdAsc();

    @Query(value = """
        SELECT m.* FROM material_category m
        JOIN material_category_applicability a ON a.material_category_id = m.id
        WHERE a.context = :context AND a.active AND m.active ORDER BY m.id
        """, nativeQuery = true)
    List<MaterialCategory> findAvailable(@Param("context") String context);

    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM material_category_applicability
        WHERE context = :context AND material_category_id = :id)
        """, nativeQuery = true)
    boolean belongsToContext(@Param("id") Long id, @Param("context") String context);

    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM material_category m
        JOIN material_category_applicability a ON a.material_category_id = m.id
        WHERE m.id = :id AND a.context = :context AND a.active AND m.active)
        """, nativeQuery = true)
    boolean isAvailable(@Param("id") Long id, @Param("context") String context);

    @Query(value = """
        SELECT m.* FROM material_category m JOIN material_category_applicability a ON a.material_category_id = m.id
        WHERE a.context = :context ORDER BY m.id
        """, nativeQuery = true)
    List<MaterialCategory> findForContext(@Param("context") String context);
}
