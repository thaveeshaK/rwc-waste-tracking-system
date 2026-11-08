param([string]$Image = 'rwc-waste-tracking:dev-validation')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$id = [guid]::NewGuid().ToString('N')
$network = "rwc-validation-$id"
$db = "rwc-db-$id"
$app = "rwc-app-$id"
$output = Join-Path $root "backend/target/container-validation-$id"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$dbPassword = [guid]::NewGuid().ToString('N')
$adminPassword = [guid]::NewGuid().ToString('N')
$staffPassword = [guid]::NewGuid().ToString('N')
$dbEnv = Join-Path $output 'db.env'
$appEnv = Join-Path $output 'app.env'
[IO.File]::WriteAllLines($dbEnv,@("POSTGRES_USER=rwc_test","POSTGRES_DB=rwc_container_test","POSTGRES_PASSWORD=$dbPassword"))
[IO.File]::WriteAllLines($appEnv,@('SPRING_PROFILES_ACTIVE=prod','PORT=9090',"DB_URL=jdbc:postgresql://${db}:5432/rwc_container_test",'DB_USERNAME=rwc_test',"DB_PASSWORD=$dbPassword",'INITIAL_ADMIN_USERNAME=container-admin',"INITIAL_ADMIN_PASSWORD=$adminPassword",'INITIAL_STAFF_USERNAME=container-staff',"INITIAL_STAFF_PASSWORD=$staffPassword",'SERVER_SERVLET_SESSION_COOKIE_SECURE=false'))
# The cookie override is for this loopback-only HTTP test. Cloud Run uses prod HTTPS cookies.
function Wait-App {
    # Docker can assign a new ephemeral host port on restart.
    $binding=(& docker port $app '9090/tcp').Trim();$script:base='http://'+$binding
    for($try=0;$try -lt 120;$try++) {
        try { $script:landingResponse=Invoke-WebRequest "$script:base/" -UseBasicParsing -TimeoutSec 2; if($script:landingResponse.StatusCode -eq 200){return} } catch {}
        Start-Sleep -Seconds 1
    }
    throw 'Container did not become ready.'
}
function Login([string]$name,[string]$password) {
    $session=New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf=Invoke-RestMethod "$script:base/api/auth/csrf" -WebSession $session
    $headers=@{}; $headers[$csrf.headerName]=$csrf.token
    Invoke-WebRequest "$script:base/api/auth/login" -Method Post -Body @{username=$name;password=$password} -Headers $headers -WebSession $session -UseBasicParsing | Out-Null
    return $session
}
function Denied([string]$path,[int]$expected,$session) {
    try { Invoke-WebRequest "$script:base$path" -WebSession $session -UseBasicParsing | Out-Null; throw 'Unexpected success.' }
    catch { if(-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $expected){throw} }
}
try {
    & docker network create $network | Out-Null; if($LASTEXITCODE -ne 0){throw 'Network creation failed.'}
    & docker run -d --name $db --network $network --env-file $dbEnv postgres:18 | Out-Null; if($LASTEXITCODE -ne 0){throw 'Test PostgreSQL failed.'}
    $ready=$false
    for($try=0;$try -lt 60;$try++) { & docker exec $db pg_isready -U rwc_test -d rwc_container_test *> $null; if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Seconds 1 }
    if(-not $ready){throw 'Test PostgreSQL was not ready.'}
    & docker run -d --name $app --network $network --memory 512m --cpus 1 -p '127.0.0.1::9090' --env-file $appEnv $Image | Out-Null; if($LASTEXITCODE -ne 0){throw 'Application container failed.'}
    $binding=(& docker port $app '9090/tcp').Trim();$script:base='http://'+$binding
    Wait-App
    if($landingResponse.Content -notmatch '<div id="root">'){throw 'Actual frontend build was not served.'}
    Invoke-WebRequest "$base/admin/settings/users" -UseBasicParsing | Out-Null
    $asset=[regex]::Match($landingResponse.Content,'/assets/[^" ]+\.js').Value
    if(-not $asset){throw 'Frontend asset missing.'};Invoke-WebRequest "$base$asset" -UseBasicParsing | Out-Null
    Denied '/api/admin/users' 401 (New-Object Microsoft.PowerShell.Commands.WebRequestSession)
    $staff=Login 'container-staff' $staffPassword;Denied '/api/admin/users' 403 $staff
    $admin=Login 'container-admin' $adminPassword
    $users=Invoke-RestMethod "$base/api/admin/users" -WebSession $admin
    if($users.totalItems -ne 2){throw 'Initial accounts were not provisioned exactly once.'}
    Invoke-WebRequest "$base/api/admin/reports/export?from=2000-01-01&to=2000-01-31&report=summary&format=xlsx" -WebSession $admin -OutFile (Join-Path $output 'report.xlsx') -UseBasicParsing | Out-Null
    $bytes=[IO.File]::ReadAllBytes((Join-Path $output 'report.xlsx'));if($bytes[0] -ne 80 -or $bytes[1] -ne 75){throw 'XLSX export failed.'}
    $target=$users.items | Where-Object username -eq 'container-staff'
    $newPassword=[guid]::NewGuid().ToString('N')
    $csrf=Invoke-RestMethod "$base/api/auth/csrf" -WebSession $admin;$headers=@{};$headers[$csrf.headerName]=$csrf.token
    Invoke-RestMethod "$base/api/admin/users/$($target.id)/password" -Method Put -Body (@{password=$newPassword;version=$target.version}|ConvertTo-Json) -ContentType application/json -Headers $headers -WebSession $admin | Out-Null
    & docker stats --no-stream --format '{{.MemUsage}}' $app | Tee-Object -FilePath (Join-Path $output 'memory.txt')
    & docker restart $app | Out-Null;Wait-App
    $null=Login 'container-staff' $newPassword
    $count=(& docker exec $db psql -U rwc_test -d rwc_container_test -Atc 'select count(*) from app_user').Trim()
    $migration=(& docker exec $db psql -U rwc_test -d rwc_container_test -Atc 'select max(version::int) from flyway_schema_history where success').Trim()
    if($count -ne '2' -or $migration -ne '19'){throw 'Bootstrap or migration verification failed.'}
    if((& docker inspect --format '{{.State.OOMKilled}}' $app).Trim() -ne 'false'){throw 'Container exhausted memory.'}
    if((& docker image inspect --format '{{.Config.User}}' $Image).Trim() -ne '10001:10001'){throw 'Runtime is not non-root.'}
    Write-Output 'PASS: prod profile, PORT=9090, packaged frontend/assets/deep link, API 401/403, login/CSRF, XLSX export, V1-V19 startup, bootstrap password retention after restart, non-root runtime and 512 MiB limit.'
    Write-Output "Logs and memory sample: $output"
} finally {
    $ErrorActionPreference = 'Continue'
    & docker logs $app 2>&1 | Out-File (Join-Path $output 'app.log')
    # Only the uniquely named local validation containers/network created above are removed.
    & docker rm -f -v $app $db 2>&1 | Out-Null
    & docker network rm $network 2>&1 | Out-Null
    Remove-Item -LiteralPath $dbEnv,$appEnv -ErrorAction SilentlyContinue
}
