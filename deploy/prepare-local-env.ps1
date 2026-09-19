$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$source = Join-Path $root 'src\main\resources\application.yml'
$target = Join-Path $PSScriptRoot '.env'
$authFile = Join-Path $PSScriptRoot 'frontend\.htpasswd'

if (Test-Path -LiteralPath $target) {
    throw 'deploy/.env already exists; preserving existing deployment credentials'
}

$keyLines = @(Select-String -LiteralPath $source -Pattern '^\s*api-key:\s*(\S+)')
if ($keyLines.Count -lt 2) { throw 'Expected DashScope and Search API keys in local application.yml' }
$dashKey = $keyLines[0].Matches.Groups[1].Value.Trim("'", '"')
$searchKey = $keyLines[-1].Matches.Groups[1].Value.Trim("'", '"')
if ([string]::IsNullOrWhiteSpace($dashKey) -or $dashKey.Contains('${')) {
    throw 'Local DashScope API key is not populated'
}

$modelLines = @(Select-String -LiteralPath $source -Pattern '^\s*model:\s*(\S+)')
$chatModel = $modelLines[0].Matches.Groups[1].Value
$embeddingModel = $modelLines[1].Matches.Groups[1].Value
$baseUrl = (Select-String -LiteralPath $source -Pattern '^\s*base-url:\s*(\S+)' | Select-Object -First 1).Matches.Groups[1].Value
$multiModel = (Select-String -LiteralPath $source -Pattern '^\s*multi-model:\s*(\S+)' | Select-Object -First 1).Matches.Groups[1].Value

$dbPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant()
$webPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(12)).ToLowerInvariant()
$sha1 = [Security.Cryptography.SHA1]::HashData([Text.Encoding]::UTF8.GetBytes($webPassword))
$hash = [Convert]::ToBase64String($sha1)
[IO.File]::WriteAllText($authFile, "fan:{SHA}$hash`n", [Text.Encoding]::ASCII)

$lines = @(
    "DB_PASSWORD=$dbPassword"
    "DASHSCOPE_API_KEY=$dashKey"
    "DASHSCOPE_BASE_URL=$baseUrl"
    "DASHSCOPE_CHAT_MODEL=$chatModel"
    "DASHSCOPE_EMBEDDING_MODEL=$embeddingModel"
    "DASHSCOPE_MULTI_MODEL=$multiModel"
    "SEARCH_API_KEY=$searchKey"
    'PEXELS_API_KEY='
    "FRONTEND_PASSWORD=$webPassword"
)
[IO.File]::WriteAllText($target, (($lines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))

Write-Output 'Created deploy/.env and frontend/.htpasswd without printing their values.'
Write-Output 'PEXELS_API_KEY is empty because no local Pexels credential was found.'
