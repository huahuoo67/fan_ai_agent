$ErrorActionPreference = 'Stop'

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$stage = Join-Path $root '.deploy-build'
$backendOut = Join-Path $PSScriptRoot 'backend\app.jar'
$mcpOut = Join-Path $PSScriptRoot 'image-mcp\app.jar'
$frontendOut = Join-Path $PSScriptRoot 'frontend\dist'

function Remove-WorkspaceDirectory([string]$path) {
    $resolved = [IO.Path]::GetFullPath($path)
    $prefix = $root.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove path outside workspace: $resolved"
    }
    if (Test-Path -LiteralPath $resolved) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

# Build the backend from an explicit allowlist. Local application.yml, tracked
# MCP credentials, test code, and demos never enter the release source tree.
Remove-WorkspaceDirectory $stage
$javaOut = Join-Path $stage 'src\main\java\com\fan\fanaiagent'
$resourcesOut = Join-Path $stage 'src\main\resources'
New-Item -ItemType Directory -Path $javaOut, $resourcesOut -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'pom.xml') -Destination $stage
$javaSource = Join-Path $root 'src\main\java\com\fan\fanaiagent'
Get-ChildItem -LiteralPath $javaSource | Where-Object Name -ne 'demo' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $javaOut -Recurse
}
Copy-Item -LiteralPath (Join-Path $root 'src\main\resources\document') -Destination $resourcesOut -Recurse
Copy-Item -LiteralPath (Join-Path $root 'src\main\resources\application.example.yml') -Destination (Join-Path $resourcesOut 'application.yml')
Copy-Item -LiteralPath (Join-Path $root 'src\main\resources\application-prod.yml') -Destination $resourcesOut
'{"mcpServers":{}}' | Set-Content -LiteralPath (Join-Path $resourcesOut 'mcp-servers.json') -Encoding utf8NoBOM

& mvn.cmd -f (Join-Path $stage 'pom.xml') '-Dmaven.test.skip=true' package
if ($LASTEXITCODE -ne 0) { throw 'Backend Maven package failed' }
Copy-Item -LiteralPath (Join-Path $stage 'target\fan-ai-agent-0.0.1-SNAPSHOT.jar') -Destination $backendOut -Force

& mvn.cmd -f (Join-Path $root 'fan-image-search-mcp-server\pom.xml') '-Dmaven.test.skip=true' package
if ($LASTEXITCODE -ne 0) { throw 'Image MCP Maven package failed' }
Copy-Item -LiteralPath (Join-Path $root 'fan-image-search-mcp-server\target\fan-image-search-mcp-server-0.0.1-SNAPSHOT.jar') -Destination $mcpOut -Force

& npm.cmd --prefix (Join-Path $root 'fan-ai-agent-frontend') ci
if ($LASTEXITCODE -ne 0) { throw 'Frontend npm ci failed' }
& npm.cmd --prefix (Join-Path $root 'fan-ai-agent-frontend') run build
if ($LASTEXITCODE -ne 0) { throw 'Frontend Vite build failed' }
Remove-WorkspaceDirectory $frontendOut
Copy-Item -LiteralPath (Join-Path $root 'fan-ai-agent-frontend\dist') -Destination $frontendOut -Recurse

Write-Output 'Release artifacts built. Tests were not compiled or run.'
Write-Output "Backend: $backendOut"
Write-Output "Image MCP: $mcpOut"
Write-Output "Frontend: $frontendOut"
