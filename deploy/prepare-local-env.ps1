$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot '.env'
$authFile = Join-Path $PSScriptRoot 'frontend\.htpasswd'
if ((Test-Path -LiteralPath $target) -or (Test-Path -LiteralPath $authFile)) {
    throw 'Existing deployment credentials are preserved. Edit them manually using .env.example.'
}
$dbPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
$webPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16)).ToLowerInvariant()
$sha1 = [Security.Cryptography.SHA1]::HashData([Text.Encoding]::UTF8.GetBytes($webPassword))
$hash = [Convert]::ToBase64String($sha1)
[IO.File]::WriteAllText($authFile, "huahuo:{SHA}$hash`n", [Text.Encoding]::ASCII)
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '.env.example'))
$postgresPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
$template = $template.Replace('CHANGE_ME_NEW_V2_PASSWORD', $dbPassword).Replace('CHANGE_ME_POSTGRES_PASSWORD', $postgresPassword).Replace('FRONTEND_PASSWORD=CHANGE_ME', "FRONTEND_PASSWORD=$webPassword")
[IO.File]::WriteAllText($target, $template, [Text.UTF8Encoding]::new($false))
Write-Output 'Created isolated v2 credentials without printing them. Fill DASHSCOPE_API_KEY before deployment.'
