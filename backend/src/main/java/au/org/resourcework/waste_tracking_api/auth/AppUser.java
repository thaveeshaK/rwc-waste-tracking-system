package au.org.resourcework.waste_tracking_api.auth;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_user")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 100, updatable = false)
    private String username;
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    @Column(nullable = false, length = 10)
    private String role;
    @Column(nullable=false)
    private boolean active = true;
    @Version @Column(nullable=false)
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
    @ManyToOne @JoinColumn(name="created_by")
    private AppUser createdBy;
    @ManyToOne @JoinColumn(name="updated_by")
    private AppUser updatedBy;
    @PrePersist
    void timestamps() {
        if(createdAt==null)createdAt=Instant.now();
        if(updatedAt==null)updatedAt=createdAt;
    }
}
