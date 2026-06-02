$MavenVersion = "3.9.6"
$MavenDir = "$PSScriptRoot\backend\.maven"
$MavenZip = "$MavenDir\maven.zip"
$MavenHome = "$MavenDir\apache-maven-$MavenVersion"
$MvnCmd = "$MavenHome\bin\mvn.cmd"

# Create .maven directory if not exists
if (-not (Test-Path $MavenDir)) {
    New-Item -ItemType Directory -Path $MavenDir | Out-Null
}

# Check if local Maven exists
if (-not (Test-Path $MvnCmd)) {
    Write-Host "========================================================" -ForegroundColor Cyan
    Write-Host "  Apache Maven not found in your system PATH." -ForegroundColor Yellow
    Write-Host "  WSMS will now download & configure a local copy..." -ForegroundColor Yellow
    Write-Host "========================================================" -ForegroundColor Cyan
    
    $Url = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
    Write-Host "Downloading Maven $MavenVersion..." -ForegroundColor Gray
    
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $Url -OutFile $MavenZip -UseBasicParsing
    } catch {
        Write-Host "Error downloading Maven. Verify your internet connection." -ForegroundColor Rose
        Write-Host "Error details: $_" -ForegroundColor Rose
        Exit 1
    }
    
    Write-Host "Extracting Maven binaries to $MavenDir..." -ForegroundColor Gray
    try {
        Expand-Archive -Path $MavenZip -DestinationPath $MavenDir -Force
        if (Test-Path $MavenZip) {
            Remove-Item $MavenZip -Force
        }
    } catch {
        Write-Host "Failed to extract Maven zip file." -ForegroundColor Rose
        Write-Host "Error details: $_" -ForegroundColor Rose
        Exit 1
    }
    
    Write-Host "Local Maven setup completed successfully!" -ForegroundColor Green
}

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  Starting Web Sites Monitoring System (WSMS)..." -ForegroundColor Green
Write-Host "  Database target: jdbc:postgresql://localhost:5432/wsms" -ForegroundColor Gray
Write-Host "  Please ensure your PostgreSQL database server is active." -ForegroundColor Yellow
Write-Host "========================================================" -ForegroundColor Cyan

# Change directory to backend folder and execute
Set-Location "$PSScriptRoot\backend"
& $MvnCmd spring-boot:run
