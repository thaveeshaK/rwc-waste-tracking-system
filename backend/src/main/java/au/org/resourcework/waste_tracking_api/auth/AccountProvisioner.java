package au.org.resourcework.waste_tracking_api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Optional initial provisioning only: never resets an existing account's credentials. */
@Component
public class AccountProvisioner implements ApplicationRunner {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String staffUsername;
    private final String staffPassword;
    private final String adminUsername;
    private final String adminPassword;

    public AccountProvisioner(AppUserRepository users, PasswordEncoder encoder,
            @Value("${app.bootstrap.staff-username}") String staffUsername,
            @Value("${app.bootstrap.staff-password}") String staffPassword,
            @Value("${app.bootstrap.admin-username}") String adminUsername,
            @Value("${app.bootstrap.admin-password}") String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.staffUsername = staffUsername;
        this.staffPassword = staffPassword;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        provision(staffUsername, staffPassword, "STAFF");
        provision(adminUsername, adminPassword, "ADMIN");
    }

    private void provision(String username, String password, String role) {
        if (username.isBlank() && password.isEmpty()) return;
        if (username.isBlank() || username.length() > 100 || password.isBlank()) {
            throw new IllegalStateException("Initial " + role + " username and password must both be supplied.");
        }
        var existing = users.findByUsername(username);
        if (existing.isPresent()) {
            if (!existing.get().getRole().equals(role)) {
                throw new IllegalStateException("Initial account has an unexpected role.");
            }
            return;
        }
        if (role.equals("STAFF") && users.existsByRole("STAFF")) {
            throw new IllegalStateException("A shared STAFF account already exists.");
        }
        var user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setRole(role);
        users.save(user);
    }
}
