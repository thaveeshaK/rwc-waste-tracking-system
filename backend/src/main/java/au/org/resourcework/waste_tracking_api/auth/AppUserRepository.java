package au.org.resourcework.waste_tracking_api.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByRole(String role);
    @org.springframework.data.jpa.repository.Query("select count(u)>0 from AppUser u where lower(u.username)=lower(:username)")
    boolean existsByUsernameIgnoringCase(@org.springframework.data.repository.query.Param("username") String username);
    long countByUsernameContainingIgnoreCase(String username);
    org.springframework.data.domain.Page<AppUser> findByUsernameContainingIgnoreCase(String username,org.springframework.data.domain.Pageable page);
    long countByRoleAndActiveTrue(String role);
}
