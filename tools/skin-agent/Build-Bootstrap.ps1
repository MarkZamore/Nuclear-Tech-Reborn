[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Java8,
    [Parameter(Mandatory)][string] $JarTool,
    [Parameter(Mandatory)][string] $LauncherSource,
    [Parameter(Mandatory)][string] $RunDirectory,
    [Parameter(Mandatory)][string] $WorkDirectory,
    [Parameter(Mandatory)][string] $OutputPath
)

$ErrorActionPreference = 'Stop'
$javac = Join-Path $Java8 'bin/javac.exe'
$source = Join-Path $PSScriptRoot 'NuclearSkinAgent.java'
$legacySkins = Join-Path $PSScriptRoot 'LegacySkinCompatibility.java'
$skinProfiles = Join-Path $PSScriptRoot 'LegacySkinProfiles.java'
$work = [IO.Path]::GetFullPath($WorkDirectory)
$output = [IO.Path]::GetFullPath($OutputPath)
$run = [IO.Path]::GetFullPath($RunDirectory)
$comparison = [StringComparison]::OrdinalIgnoreCase
$separator = [IO.Path]::DirectorySeparatorChar
if ((Split-Path -Leaf (Split-Path -Parent $run)) -cne 'agent-runs' -or
    !$work.StartsWith($run + $separator, $comparison) -or !$output.StartsWith($run + $separator, $comparison) -or
    !(Test-Path -LiteralPath (Join-Path $run 'RUN.json') -PathType Leaf)) {
    throw 'Use a single owned workspace run for the classes and output.'
}
if (!(Test-Path -LiteralPath $javac -PathType Leaf) -or !(Test-Path -LiteralPath $JarTool -PathType Leaf) -or
    (Test-Path -LiteralPath $work) -or (Test-Path -LiteralPath $output)) {
    throw 'Use the exact Java 8 JDK, a modern JAR tool, and fresh output paths in the owned build run.'
}
$classes = [IO.Directory]::CreateDirectory((Join-Path $work 'classes')).FullName
$manifest = Join-Path $work 'agent.mf'
$manifestText = "Manifest-Version: 1.0`nPremain-Class: lanminecraft.nuclear.skins.NuclearSkinAgent`n"
[IO.File]::WriteAllText($manifest, $manifestText, [Text.Encoding]::ASCII)
$encoding = [Text.UTF8Encoding]::new($false)
$launcherRevision = 'a5946d4ab5967ef164b7fa15636980178001e937'
$generated = foreach ($name in 'PortableSkinProfiles', 'PortableIdentityReflection') {
    $relative = "Program/IdentityAdapters/Common/$name.java"
    $lines = git -C $LauncherSource show "${launcherRevision}:$relative"
    if ($LASTEXITCODE -ne 0) { throw "Missing the pinned launcher skin source: $relative" }
    $text = ($lines -join "`n").
        Replace('package minecraft.portable.identity;', 'package lanminecraft.nuclear.skins;').
        Replace('profile.getClass().isRecord()', 'false').
        Replace('value.isBlank()', 'value.trim().isEmpty()').
        Replace('Path.of(configuredPath)', 'java.nio.file.Paths.get(configuredPath)')
    if ($name -eq 'PortableSkinProfiles') {
        $getter = @'
    static String registeredUrl(UUID id) {
        SkinEntry entry = loadEntries().get(id);
        return entry == null ? null : entry.url();
    }

'@
        $signature = '    public static boolean isRegisteredUrl(String url) {'
        if (!$text.Contains($signature)) { throw 'Pinned skin registry API shape changed.' }
        $text = $text.Replace($signature, $getter + "`n" + $signature)
    }
    $path = Join-Path $work "$name.java"
    [IO.File]::WriteAllText($path, $text + "`n", $encoding)
    $path
}
& $javac '-XDignore.symbol.file' -encoding UTF-8 -source 8 -target 8 -d $classes $source $legacySkins $skinProfiles @generated
if ($LASTEXITCODE -ne 0) { throw 'Java 8 bootstrap compilation failed.' }
& $JarTool --create --file $output --manifest $manifest --date 2000-01-01T00:00:00Z -C $classes lanminecraft
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap JAR packaging failed.' }
Get-FileHash -LiteralPath $output -Algorithm SHA256
