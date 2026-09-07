param(
    [ValidateSet('arm64-v8a', 'x86_64', 'all')]
    [string]$Abi = 'all'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$androidSdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\AI\audio\android-sdk' }
$androidNdk = Join-Path $androidSdk 'ndk\27.3.13750724'
$cmake = Join-Path $androidSdk 'cmake\3.22.1\bin\cmake.exe'
$ninja = Join-Path $androidSdk 'cmake\3.22.1\bin\ninja.exe'
$toolchain = Join-Path $androidNdk 'build\cmake\android.toolchain.cmake'
$llamaCommit = '6c8dcaa7ae41fa9f4aa2b3b68ee82cb8b2a03632'
$workRoot = Join-Path $repositoryRoot '.runtime-build'
$llamaRoot = Join-Path $workRoot 'llama.cpp'
$packageRoot = Join-Path $repositoryRoot '.localcore-packages'

foreach ($required in @($cmake, $ninja, $toolchain)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "缺少构建工具: $required" }
}

if (-not (Test-Path -LiteralPath (Join-Path $llamaRoot '.git'))) {
    New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
    git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git $llamaRoot
}
git -C $llamaRoot fetch --depth 1 origin $llamaCommit
git -C $llamaRoot checkout --detach $llamaCommit

$abis = if ($Abi -eq 'all') { @('arm64-v8a', 'x86_64') } else { @($Abi) }
New-Item -ItemType Directory -Force -Path $packageRoot | Out-Null

foreach ($targetAbi in $abis) {
    $buildDirectory = Join-Path $workRoot "build-$targetAbi"
    & $cmake -S (Join-Path $repositoryRoot 'runtime') -B $buildDirectory -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
        "-DANDROID_ABI=$targetAbi" `
        '-DANDROID_PLATFORM=android-28' `
        '-DCMAKE_BUILD_TYPE=Release' `
        "-DLLAMA_CPP_SOURCE_DIR=$llamaRoot"
    if ($LASTEXITCODE -ne 0) { throw "配置动态核心失败: $targetAbi" }
    & $cmake --build $buildDirectory --target localcore_runtime --parallel
    if ($LASTEXITCODE -ne 0) { throw "编译动态核心失败: $targetAbi" }

    $library = Get-ChildItem -LiteralPath $buildDirectory -Recurse -Filter 'liblocalcore_runtime.so' | Select-Object -First 1
    if (-not $library) { throw "未找到动态核心产物: $targetAbi" }
    $stage = Join-Path $workRoot "package-$targetAbi"
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    Copy-Item -LiteralPath $library.FullName -Destination (Join-Path $stage 'liblocalcore_runtime.so')
    $archive = Join-Path $packageRoot "localcore-runtime-llama-b10256-lc2-$targetAbi.zip"
    if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
    Compress-Archive -LiteralPath (Join-Path $stage 'liblocalcore_runtime.so') -DestinationPath $archive -CompressionLevel Optimal
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $archive).Length
    Write-Output "$targetAbi $size $hash $archive"
}
