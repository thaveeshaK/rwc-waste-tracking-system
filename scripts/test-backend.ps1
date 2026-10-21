param([string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin', [switch]$Browser)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'
$testRoot = Join-Path $backendRoot ('target\postgres-test-' + [guid]::NewGuid().ToString('N'))
$dataPath = Join-Path $testRoot 'data'
$passwordPath = Join-Path $testRoot 'password.tmp'
$started = $false
$exitCode = 1
$variables = @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'PGPASSWORD', 'RUN_DB_TESTS',
    'INITIAL_STAFF_USERNAME', 'INITIAL_STAFF_PASSWORD', 'INITIAL_ADMIN_USERNAME', 'INITIAL_ADMIN_PASSWORD',
    'RWC_TEST_DB_URL', 'RWC_TEST_DB_USERNAME', 'RWC_TEST_DB_PASSWORD', 'RWC_TEST_DB_TOKEN',
    'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS',
    'SPRING_CONFIG_LOCATION', 'SPRING_APPLICATION_JSON') +
    @(Get-ChildItem Env: | Where-Object { $_.Name -like 'SPRING_*' } | Select-Object -ExpandProperty Name)
$variables = @($variables | Select-Object -Unique)
$previousValues = @{}
foreach ($name in $variables) { $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

try {
    # Remove inherited Spring connection/pool/Flyway properties, config imports/profiles,
    # and JVM/Maven property injection. Restore them all in finally.
    foreach ($name in $variables) {
        if ($name -like 'SPRING_*' -or $name -in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')) {
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
    }
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    $testPassword = [guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText($passwordPath, $testPassword)
    & "$PostgresBin\initdb.exe" -D $dataPath -U rwc_test --auth=scram-sha-256 --pwfile=$passwordPath --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) { throw 'Failed to initialize the isolated test database.' }
    Remove-Item -LiteralPath $passwordPath
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $testPort = $listener.LocalEndpoint.Port
    $listener.Stop()
    & "$PostgresBin\pg_ctl.exe" -D $dataPath -l (Join-Path $testRoot 'postgres.log') -o "-h 127.0.0.1 -p $testPort" -w start
    if ($LASTEXITCODE -ne 0) { throw 'Failed to start the isolated test database.' }
    $started = $true
    $env:PGPASSWORD = $testPassword
    & "$PostgresBin\createdb.exe" -h 127.0.0.1 -p $testPort -U rwc_test rwc_slice_test
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create the test database.' }
    $isolationToken = [guid]::NewGuid().ToString()
    & "$PostgresBin\psql.exe" -X -w -h 127.0.0.1 -p $testPort -U rwc_test -d rwc_slice_test -v ON_ERROR_STOP=1 -c "COMMENT ON DATABASE rwc_slice_test IS '$isolationToken'"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to mark the isolated test database.' }
    $identity = & "$PostgresBin\psql.exe" -X -w -h 127.0.0.1 -p $testPort -U rwc_test -d rwc_slice_test -v ON_ERROR_STOP=1 -Atc "SELECT current_database() || '|' || current_user || '|' || host(inet_server_addr()) || '|' || inet_server_port() || '|' || shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()"
    if ($LASTEXITCODE -ne 0 -or $identity -ne "rwc_slice_test|rwc_test|127.0.0.1|$testPort|$isolationToken") {
        throw 'Temporary database identity verification failed; tests will not start.'
    }
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:$testPort/rwc_slice_test"
    $env:DB_USERNAME = 'rwc_test'
    $env:DB_PASSWORD = $testPassword
    $env:RWC_TEST_DB_URL = $env:DB_URL
    $env:RWC_TEST_DB_USERNAME = $env:DB_USERNAME
    $env:RWC_TEST_DB_PASSWORD = $testPassword
    $env:RWC_TEST_DB_TOKEN = $isolationToken
    $env:SPRING_CONFIG_LOCATION = 'classpath:/application.properties'
    # Explicitly pin both datasource variants and Flyway, including credentials and schema.
    $env:SPRING_APPLICATION_JSON = @{
        'spring.datasource.url' = $env:DB_URL
        'spring.datasource.username' = $env:DB_USERNAME
        'spring.datasource.password' = $testPassword
        'spring.datasource.driver-class-name' = 'org.postgresql.Driver'
        'spring.datasource.hikari.jdbc-url' = $env:DB_URL
        'spring.datasource.hikari.username' = $env:DB_USERNAME
        'spring.datasource.hikari.password' = $testPassword
        'spring.datasource.hikari.schema' = 'public'
        'spring.flyway.url' = $env:DB_URL
        'spring.flyway.user' = $env:DB_USERNAME
        'spring.flyway.password' = $testPassword
        'spring.flyway.default-schema' = 'public'
        'spring.flyway.schemas' = 'public'
    } | ConvertTo-Json -Compress
    $env:RUN_DB_TESTS = 'true'
    $env:INITIAL_STAFF_USERNAME = 'test-staff'
    $env:INITIAL_ADMIN_USERNAME = 'test-admin'
    $env:INITIAL_STAFF_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:INITIAL_ADMIN_PASSWORD = [guid]::NewGuid().ToString('N')
    Push-Location $backendRoot
    try {
        & mvn.cmd -B -ntp verify
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    if ($exitCode -eq 0 -and $Browser) {
        Push-Location (Join-Path $projectRoot 'frontend')
        try {
            & npm.cmd run build
            if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
            & npm.cmd run lint
            if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
        } finally { Pop-Location }
        Push-Location $projectRoot
        try {
            & node scripts/test-browser.mjs
            $exitCode = $LASTEXITCODE
        } finally { Pop-Location }
    }
} finally {
    if ($started) { & "$PostgresBin\pg_ctl.exe" -D $dataPath -m fast -w stop }
    if (Test-Path -LiteralPath $passwordPath) { Remove-Item -LiteralPath $passwordPath }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previousValues[$name], 'Process') }
}
exit $exitCode
