param(
    [string]$LlamaTag = 'v0.4.0',
    [string]$CoreVersion = '0.4.0-localcore.1',
    [string[]]$Abis = @('arm64-v8a', 'x86_64')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$llamaDir = Join-Path $repoRoot '.runtime-build\llama.cpp'
$buildRoot = Join-Path $repoRoot '.runtime-build\core'
$distRoot = Join-Path $repoRoot '.runtime-build\dist'
$ndk = 'D:\AI\audio\android-sdk\ndk\27.3.13750724'
$toolchain = Join-Path $ndk 'build\cmake\android.toolchain.cmake'

if (-not (Test-Path (Join-Path $llamaDir '.git'))) {
    git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git $llamaDir
}
git -C $llamaDir fetch --depth 1 origin "refs/tags/$LlamaTag`:refs/tags/$LlamaTag"
git -C $llamaDir checkout --detach $LlamaTag
$commit = (git -C $llamaDir rev-parse HEAD).Trim()

New-Item -ItemType Directory -Force $buildRoot, $distRoot | Out-Null
$packageRoot = Join-Path $distRoot "localcore-core-$CoreVersion"
if (Test-Path $packageRoot) { Remove-Item -LiteralPath $packageRoot -Recurse -Force }

foreach ($abi in $Abis) {
    $buildDir = Join-Path $buildRoot $abi
    cmake -S (Join-Path $repoRoot 'native\core') -B $buildDir -G Ninja `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
        "-DANDROID_ABI=$abi" `
        '-DANDROID_PLATFORM=android-28' `
        '-DANDROID_STL=c++_shared' `
        "-DLLAMA_CPP_DIR=$llamaDir" `
        "-DLOCALCORE_CORE_VERSION=$CoreVersion" `
        "-DLOCALCORE_LLAMA_VERSION=$LlamaTag-$commit" `
        '-DLOCALCORE_CORE_ABI_VERSION=1' `
        '-DCMAKE_BUILD_TYPE=Release'
    cmake --build $buildDir --target localcore_core --parallel
    $library = Get-ChildItem -Path $buildDir -Filter 'liblocalcore_core.so*' -Recurse -File |
        Where-Object { $_.Name -eq 'liblocalcore_core.so' } | Select-Object -First 1
    if ($null -eq $library) { throw "找不到 $abi 的 liblocalcore_core.so" }
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
Compress-Archive -Path (Join-Path $packageRoot '*') -DestinationPath $zip -CompressionLevel Optimal
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
