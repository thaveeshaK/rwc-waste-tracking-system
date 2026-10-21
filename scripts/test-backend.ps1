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
    'INITIAL_STAFF_USERNAME', 'INITIAL_STAFF_PASSWORD', 'INITIAL_ADMIN_USERNAME', 'INITIAL_ADMIN_PASSWORD')
$previousValues = @{}
foreach ($name in $variables) { $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

try {
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
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:$testPort/rwc_slice_test"
    $env:DB_USERNAME = 'rwc_test'
    $env:DB_PASSWORD = $testPassword
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
