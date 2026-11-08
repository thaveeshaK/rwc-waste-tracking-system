# One-time DEV admin password recovery

Use only for `rwc-waste-tracking-dev`, database `rwc_waste_tracking`.
This procedure is manual; the helper never accesses GCP or a database.
If another working Admin exists, prefer the existing User Management password reset.

## Generate the hash locally

From the repository root in an interactive PowerShell terminal, with Java 21 and Maven available:

```powershell
mvn.cmd -f backend/pom.xml dependency:build-classpath '-Dmdep.outputFile=target/recovery-classpath.txt'
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve application dependencies.' }
$recoveryClasspath = (Get-Content backend/target/recovery-classpath.txt -Raw).Trim()
java --class-path "$recoveryClasspath" scripts/EncodeDevPassword.java
```

The helper uses the same `new BCryptPasswordEncoder()` as `SecurityConfig`:
BCrypt version `$2a$`, cost 10, random salt, with no `{bcrypt}` prefix.
Enter the new password twice at the hidden prompts. Only the encoded hash is
written to stdout; prompts/errors go to stderr. Do not supply the password in
arguments, environment variables, source files or SQL. The existing password rule
is non-blank and at most 72 UTF-8 bytes. Choose a strong unique password.
The helper verifies that the generated hash matches before printing it.

## Apply manually to DEV

Use an authorised PostgreSQL client through the existing approved Cloud SQL access
method. Explicitly select the DEV project/instance
`rwc-waste-tracking-dev:australia-southeast2:rwc-waste-db-dev` and database
`rwc_waste_tracking`. The database name alone cannot distinguish DEV from another
environment. Do not paste a plaintext password into the client.

Avoid concurrent User Management changes during recovery. In a single interactive
SQL session, run:

```sql
BEGIN;
LOCK TABLE public.app_user IN SHARE ROW EXCLUSIVE MODE;
SELECT current_database(), current_user;
SELECT id, username, role, active, version
FROM public.app_user WHERE username = 'admin' FOR UPDATE;
```

Confirm exactly one row, username `admin`, role `ADMIN`, active `true`, and the
correct DEV connection. Otherwise `ROLLBACK;` and investigate; do not change role
or status as part of this procedure.

Replace the placeholder below with the complete generated 60-character hash.
The SQL changes only `password_hash`; no account identity, role, status,
ownership, metadata or operational records are changed.

```sql
DO $recovery$
DECLARE
    new_hash TEXT := '<PASTE_COMPLETE_BCRYPT_HASH_HERE>';
    affected INTEGER;
BEGIN
    IF current_database() <> 'rwc_waste_tracking' THEN
        RAISE EXCEPTION 'Wrong database';
    END IF;
    IF new_hash !~ '^\$2a\$10\$[./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'Expected complete application BCrypt hash';
    END IF;
    UPDATE public.app_user
    SET password_hash = new_hash
    WHERE username = 'admin' AND role = 'ADMIN' AND active = true;
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one active admin account, updated %', affected;
    END IF;
END
$recovery$;
COMMIT;
```

On any error, run `ROLLBACK;`; never weaken the account predicates. Do not leave
the transaction open. Use a SQL editor or a single-quoted PowerShell here-string
if saving SQL temporarily: PowerShell double-quoted strings expand BCrypt `$`
characters. Treat the hash as sensitive; do not commit it or share query logs.

## Verify

Sign in as `admin` with the new password in a fresh browser session. The previous
password should fail a fresh login. Staff credentials and historical records
remain unchanged. AccountProvisioner still creates missing accounts only; Secret
Manager version 2 does not overwrite an existing account, and this SQL does not
change Secret Manager.

Direct SQL bypasses application session revocation and optimistic-version updates.
Keep User Management idle during recovery and reload any open user-management
pages afterward. Existing authenticated sessions remain valid: if old sessions
must be revoked, arrange a separate, authorised Cloud Run restart/revision change
for this single-instance DEV service. This procedure does not perform one.
