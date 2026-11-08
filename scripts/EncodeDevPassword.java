import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Local-only recovery helper. Does not start Spring or connect to a database. */
public class EncodeDevPassword {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Do not pass passwords as arguments.");
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Run interactively in a terminal; redirected password input is not supported.");
        char[] password = null;
        char[] confirmation = null;
        try {
            System.err.print("New DEV admin password: ");
            password = console.readPassword();
            System.err.print("Confirm password: ");
            confirmation = console.readPassword();
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation))
                throw new IllegalArgumentException("Passwords must match.");
            String value = new String(password);
            if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 72)
                throw new IllegalArgumentException("Password must not be blank and must contain at most 72 UTF-8 bytes.");
            // Matches SecurityConfig.passwordEncoder(): default BCrypt, no {bcrypt} prefix.
            var encoder = new BCryptPasswordEncoder();
            String hash = encoder.encode(value);
            if (!encoder.matches(value, hash)) throw new IllegalStateException("Hash verification failed.");
            System.out.println(hash);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
