$PSScriptRoot = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
if (-not $PSScriptRoot) { $PSScriptRoot = "." }

# Check if port 8081 is already in use by a zombie/stale process and stop it
$Port8081Process = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($Port8081Process) {
    $PidToKill = $Port8081Process.OwningProcess
    Write-Host "Port 8081 is in use by stale process ID $PidToKill. Terminating it to ensure a clean launch..." -ForegroundColor Yellow
    Stop-Process -Id $PidToKill -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
}

# Check Java version compatibility (Spring Boot 3.2.x does not support Java 22+)
$JavaBin = "java"
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $JavaBin = "$env:JAVA_HOME\bin\java.exe"
}

$JavaVersionOutput = & $JavaBin -version 2>&1
$JavaVersionStr = [string]::Join(" ", $JavaVersionOutput)
$VersionMatch = [regex]::Match($JavaVersionStr, '(version|build)\s+"?([0-9]+)\.?')
$JavaMajorVersion = 0

if ($VersionMatch.Success) {
    $VersionString = $VersionMatch.Groups[2].Value
    if ($VersionString -eq "1") {
        $VersionMatch2 = [regex]::Match($JavaVersionStr, 'version\s+"?1\.([0-9]+)\.?')
        if ($VersionMatch2.Success) {
            $JavaMajorVersion = [int]$VersionMatch2.Groups[1].Value
        } else {
            $JavaMajorVersion = 8
        }
    } else {
        $JavaMajorVersion = [int]$VersionString
    }
}

if ($JavaMajorVersion -ge 22 -or $JavaMajorVersion -eq 0) {
    Write-Host "Current Java version ($JavaMajorVersion) is 22 or higher (or undetected), which is incompatible with Spring Boot 3.2.x." -ForegroundColor Yellow
    Write-Host "Searching for a compatible Java runtime (Java 17 or 21) in VS Code extensions..." -ForegroundColor Yellow
    
    $VsCodeJavaHome = Get-ChildItem -Path "$env:USERPROFILE\.vscode\extensions\" -Filter "redhat.java-*" -Directory -ErrorAction SilentlyContinue | 
        ForEach-Object { Get-ChildItem -Path $_.FullName -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue } | 
        Select-Object -ExpandProperty FullName -First 1
        
    if ($VsCodeJavaHome) {
        $CompatibleJavaHome = Split-Path -Parent (Split-Path -Parent $VsCodeJavaHome)
        if (Test-Path $CompatibleJavaHome) {
            $env:JAVA_HOME = $CompatibleJavaHome
            Write-Host "Successfully redirected JAVA_HOME to compatible VS Code JRE: $CompatibleJavaHome" -ForegroundColor Green
        }
    } else {
        Write-Host "Warning: No compatible VS Code Java JRE/JDK (17 or 21) was found. Maven build might fail." -ForegroundColor Red
    }
}

$LocalMavenHome = "$PSScriptRoot\apache-maven-3.9.10"
$LocalMvnCmd = "$LocalMavenHome\bin\mvn.cmd"
$MvnCmd = ""

# 1. Check if the pre-existing maven folder exists in the workspace root
if (Test-Path $LocalMvnCmd) {
    Write-Host "========================================================" -ForegroundColor Cyan
    Write-Host "Found pre-existing local Maven at: $LocalMavenHome" -ForegroundColor Green
    Write-Host "========================================================" -ForegroundColor Cyan
    $MvnCmd = $LocalMvnCmd
} else {
    # 2. Check if maven is globally available in PATH
    $MvnCheck = Get-Command mvn -ErrorAction SilentlyContinue
    if ($MvnCheck) {
        Write-Host "Found global Maven in system PATH" -ForegroundColor Green
        $MvnCmd = "mvn"
    } else {
        # 3. Fallback: Download local Maven if missing
        $MavenVersion = "3.9.6"
        $MavenDir = "$PSScriptRoot\backend\.maven"
        $MavenZip = "$MavenDir\maven.zip"
        $MavenHome = "$MavenDir\apache-maven-$MavenVersion"
        $FallbackMvnCmd = "$MavenHome\bin\mvn.cmd"
        
        if (-not (Test-Path $MavenDir)) {
            New-Item -ItemType Directory -Path $MavenDir | Out-Null
        }
        
        if (Test-Path $FallbackMvnCmd) {
            $MvnCmd = $FallbackMvnCmd
        } else {
            Write-Host "Local Maven not found. Downloading fallback..." -ForegroundColor Yellow
            $Url = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
            try {
                [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                Invoke-WebRequest -Uri $Url -OutFile $MavenZip -UseBasicParsing
                Expand-Archive -Path $MavenZip -DestinationPath $MavenDir -Force
                Remove-Item $MavenZip -Force
                $MvnCmd = $FallbackMvnCmd
            } catch {
                Write-Host "Failed to download Maven. Verify your internet connection." -ForegroundColor Rose
                Write-Host "Error details: $_" -ForegroundColor Rose
                Exit 1
            }
        }
    }
}
# Check and start Ollama service
Write-Host "Checking local Ollama service status..." -ForegroundColor Cyan

# 1. First trigger WSL boot and service start if running WSL distribution
$WslCheck = Get-Command wsl -ErrorAction SilentlyContinue
if ($WslCheck) {
    Write-Host "WSL detected. Ensuring Ollama service is active..." -ForegroundColor Green
    & wsl -u root systemctl start ollama 2>&1 | Out-Null
    & wsl ollama list 2>&1 | Out-Null
}

# 2. Get the WSL IP dynamically to support direct host-to-VM connections
$WslIp = ""
if ($WslCheck) {
    try {
        $WslIp = (wsl hostname -I).Trim().Split(" ")[0]
        if ($WslIp) {
            Write-Host "Detected WSL internal IP address: $WslIp" -ForegroundColor Green
        }
    } catch {
        # Fallback to localhost loopbacks
    }
}

# 3. Assemble target endpoints (prioritize direct WSL IP for speed and reliability)
$LoopbackUrls = @()
if ($WslIp) {
    $LoopbackUrls += "http://${WslIp}:11434"
}
$LoopbackUrls += "http://127.0.0.1:11434"
$LoopbackUrls += "http://[::1]:11434"

$WorkingBaseUrl = ""
$OllamaOnline = $false

# 4. Check if already online on any endpoint
foreach ($Url in $LoopbackUrls) {
    try {
        $Response = Invoke-RestMethod -Uri "$Url/api/tags" -Method Get -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($Response) {
            $WorkingBaseUrl = $Url
            $OllamaOnline = $true
            break
        }
    } catch {
        # Continue checking
    }
}

if (-not $OllamaOnline) {
    Write-Host "Ollama service is not responding. Attempting to start native Ollama..." -ForegroundColor Yellow
    
    # Try starting native Windows Ollama if available
    $OllamaExe = ""
    if (Get-Command ollama -ErrorAction SilentlyContinue) {
        $OllamaExe = "ollama"
    } elseif (Test-Path "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe") {
        $OllamaExe = "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe"
    } elseif (Test-Path "$env:ProgramFiles\Ollama\ollama.exe") {
        $OllamaExe = "$env:ProgramFiles\Ollama\ollama.exe"
    }
    
    if ($OllamaExe) {
        Write-Host "Found native Windows Ollama installation. Starting..." -ForegroundColor Green
        Start-Process -FilePath $OllamaExe -ArgumentList "serve" -WindowStyle Minimized -PassThru | Out-Null
    }
    
    # Wait up to 30 seconds for Ollama port to open
    Write-Host "Waiting for Ollama service to become responsive..." -ForegroundColor Yellow
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        foreach ($Url in $LoopbackUrls) {
            try {
                $Response = Invoke-RestMethod -Uri "$Url/api/tags" -Method Get -TimeoutSec 1 -ErrorAction SilentlyContinue
                if ($Response) {
                    Write-Host "Ollama is now online and listening on $Url!" -ForegroundColor Green
                    $WorkingBaseUrl = $Url
                    $OllamaOnline = $true
                    break
                }
            } catch {
                # Continue waiting
            }
        }
        if ($OllamaOnline) { break }
    }
    
    if (-not $OllamaOnline) {
        Write-Host "Warning: Ollama service failed to respond within 30 seconds. The chatbot may not be available." -ForegroundColor Red
    }
} else {
    Write-Host "Ollama service is already active and running on: $WorkingBaseUrl" -ForegroundColor Green
}

# Inject the working loopback URL to the environment so the Spring Boot backend uses it
if ($OllamaOnline -and $WorkingBaseUrl) {
    $env:OLLAMA_BASE_URL = $WorkingBaseUrl
    Write-Host "Successfully bound OLLAMA_BASE_URL env var to: $WorkingBaseUrl" -ForegroundColor Green
}

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  Starting Web Sites Monitoring System (WSMS) on Port 8081..." -ForegroundColor Green
Write-Host "  Database target: jdbc:postgresql://localhost:5432/wsms" -ForegroundColor Gray
Write-Host "========================================================" -ForegroundColor Cyan

# Change directory to backend folder and execute
Set-Location "$PSScriptRoot\backend"
& $MvnCmd clean spring-boot:run
