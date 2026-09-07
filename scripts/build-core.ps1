param(
    [string]$LlamaTag = 'v0.4.0',
    [string]$CoreVersion = '0.4.0-localcore.4',
    [string[]]$Abis = @('arm64-v8a', 'x86_64')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$llamaDir = Join-Path $repoRoot '.runtime-build\llama.cpp'
$buildRoot = Join-Path $repoRoot '.runtime-build\core'
$distRoot = Join-Path $repoRoot '.runtime-build\dist'
$ndk = 'D:\AI\audio\android-sdk\ndk\27.3.13750724'
$toolchain = Join-Path $ndk 'build\cmake\android.toolchain.cmake'
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot' }
$jar = Join-Path $javaHome 'bin\jar.exe'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "jar.exe not found: $jar" }

if (-not (Test-Path (Join-Path $llamaDir '.git'))) {
    git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git $llamaDir
    if ($LASTEXITCODE -ne 0) { throw 'llama.cpp clone failed' }
}
git -C $llamaDir fetch --depth 1 origin "refs/tags/$LlamaTag`:refs/tags/$LlamaTag"
if ($LASTEXITCODE -ne 0) { throw 'llama.cpp fetch failed' }
git -C $llamaDir checkout --detach $LlamaTag
if ($LASTEXITCODE -ne 0) { throw 'llama.cpp checkout failed' }
$commit = (git -C $llamaDir rev-parse HEAD).Trim()

New-Item -ItemType Directory -Force $buildRoot, $distRoot | Out-Null
$packageRoot = Join-Path $distRoot "localcore-core-$CoreVersion"
$resolvedDistRoot = [System.IO.Path]::GetFullPath($distRoot).TrimEnd('\') + '\'
$resolvedPackageRoot = [System.IO.Path]::GetFullPath($packageRoot)
if (-not $resolvedPackageRoot.StartsWith($resolvedDistRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Package directory escaped dist root: $resolvedPackageRoot"
}
if (Test-Path $packageRoot) { Remove-Item -LiteralPath $resolvedPackageRoot -Recurse -Force }

foreach ($abi in $Abis) {
    $buildDir = Join-Path $buildRoot $abi
    cmake -S (Join-Path $repoRoot 'native\core') -B $buildDir -G Ninja `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
        "-DANDROID_ABI=$abi" `
        '-DANDROID_PLATFORM=android-28' `
        '-DANDROID_STL=c++_static' `
        "-DLLAMA_CPP_DIR=$llamaDir" `
        "-DLOCALCORE_CORE_VERSION=$CoreVersion" `
        "-DLOCALCORE_LLAMA_VERSION=$LlamaTag-$commit" `
        '-DLOCALCORE_CORE_ABI_VERSION=1' `
        '-DCMAKE_BUILD_TYPE=Release'
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed for $abi" }
    cmake --build $buildDir --target localcore_core --parallel
    if ($LASTEXITCODE -ne 0) { throw "Core build failed for $abi" }
    $library = Get-ChildItem -Path $buildDir -Filter 'liblocalcore_core.so*' -Recurse -File |
        Where-Object { $_.Name -eq 'liblocalcore_core.so' } | Select-Object -First 1
    if ($null -eq $library) { throw "liblocalcore_core.so not found for $abi" }
    $target = Join-Path $packageRoot "jniLibs\$abi"
    New-Item -ItemType Directory -Force $target | Out-Null
    Copy-Item -LiteralPath $library.FullName -Destination (Join-Path $target 'liblocalcore_core.so')
}

$metadata = [ordered]@{
    schemaVersion = 1
    coreAbi = 1
    coreVersion = $CoreVersion
    llamaTag = $LlamaTag
    llamaCommit = $commit
    abis = $Abis
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $packageRoot 'core.json')
$zip = Join-Path $distRoot "localcore-core-$CoreVersion-android.zip"
if (Test-Path $zip) { Remove-Item -LiteralPath $zip -Force }
Push-Location $packageRoot
try {
    & $jar --create --file $zip .
    if ($LASTEXITCODE -ne 0) { throw 'Core package creation failed' }
} finally {
    Pop-Location
}
$hash = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
$size = (Get-Item $zip).Length
$assetUrl = "https://github.com/CCSSNE/LocalCore/releases/download/core-stable/$(Split-Path -Leaf $zip)"
$variants = [ordered]@{}
foreach ($abi in $Abis) {
    $variants[$abi] = [ordered]@{
        url = $assetUrl
        size = $size
        sha256 = $hash
        entry = "jniLibs/$abi/liblocalcore_core.so"
    }
}
$manifest = [ordered]@{
    schemaVersion = 1
    core = [ordered]@{
        id = 'localcore.core'
        type = 'core'
        version = $CoreVersion
        coreAbi = 1
        llamaTag = $LlamaTag
        llamaCommit = $commit
        variants = $variants
    }
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $distRoot 'core-manifest.json')
Write-Output $zip
Write-Output (Join-Path $distRoot 'core-manifest.json')
Write-Output "sha256=$hash size=$size llama=$commit"
