package au.org.resourcework.waste_tracking_api.users;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class UserData {
    private UserData() {}
    public enum Role { STAFF, ADMIN }
    public record UserResponse(Long id,String username,String role,boolean active,Long version,
            Instant createdAt,Instant updatedAt,String createdBy,String updatedBy) {}
    public record Create(@NotBlank @Size(max=100) String username,@NotNull String password,@NotNull Role role) {}
    public record ChangeRole(@NotNull Role role,@NotNull @PositiveOrZero Long version) {}
    public record Status(@NotNull Boolean active,@NotNull @PositiveOrZero Long version) {}
    public record Password(@NotNull String password,@NotNull @PositiveOrZero Long version) {}
}
