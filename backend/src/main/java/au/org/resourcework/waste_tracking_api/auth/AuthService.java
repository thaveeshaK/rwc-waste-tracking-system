package au.org.resourcework.waste_tracking_api.auth;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AuthService {
    private final AppUserRepository users;
    private final Clock clock;

    public AuthService(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    public UserResponse currentUser(String username) {
        var user = users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in."));
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), LocalDate.now(clock));
    }

    public record UserResponse(Long id, String username, String role, LocalDate operationalDate) {}
}
