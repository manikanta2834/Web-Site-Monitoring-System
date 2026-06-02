# Web Sites Monitoring System (WSMS)

WSMS is an open-source, lightweight, high-performance **uptime and digital perimeter tracking platform** inspired by enterprise tools like Site24x7 and Datadog Synthetics. 

Designed to be highly targeted and beginner-friendly, WSMS acts as a "black-box" tracking agent, performing automated parallel probes to check site availability, smooth latency metrics using an Exponential Weighted Moving Average (EWMA) algorithm, parse SSL/TLS certificate chains for expiration dates, and suppress network blip alarms via a 3x rapid-retry verification mechanism.

---

## 🚀 Key Features

* **Glassmorphic Observability Console**: A premium, highly responsive Vue 3 dashboard styled with Tailwind CSS and telemetry trend visualizations powered by Chart.js.
* **Asynchronous Multi-Threaded Probing**: High-throughput non-blocking monitoring engine running concurrent HTTP client requests using dedicated Spring Thread Pools.
* **3x Fail-Safe Retry Validation**: When a check fails (timeouts or server errors), the prober automatically executes 3 rapid re-checks over 30 seconds before declaring a state as `DOWN`.
* **EWMA Response Smoother**: Applies a rolling exponential average mathematical filter ($\alpha = 0.3$) to latency trends, discarding temporary internet routing fluctuations for stable trends.
* **SSL Certificate Expiry Monitor**: Socket-level cryptographical parsing that extracts peer x509 certificates from HTTPS TLS connections on port 443 to alert on certificate lifetimes.
* **Pre-Flight Connectivity Checks**: New endpoints are tested immediately in the background upon registration to guarantee immediate dashboard responsiveness.

---

## 🛠 Tech Stack

* **Frontend**: Vue.js 3 (Composition API), Axios, Tailwind CSS, Chart.js.
* **Backend**: Java Spring Boot 3, Spring Data JPA, Spring Scheduler, Concurrent Executors.
* **Database Ledger**: PostgreSQL (Time-Series historical checks).
* **Developer Experience Options**:
  1. *Unified Execution (Recommended)*: Vue 3 assets are compiled directly inside Spring Boot's static resources. Running Maven boots both the API and the web console on a single port (`8081`) with zero CORS complications.
  2. *Decoupled Execution*: Independent Vite hot-reloading dev server on port `5173` proxying requests to the backend API on port `8081`.

---

## 📋 System Prerequisites

Ensure you have the following installed on your developer machine:
* **Java SDK 17 or higher** (Oracle or Eclipse Temurin)
* **Apache Maven 3.8+**
* **PostgreSQL 14+**
* **Node.js v18+ & npm** (Optional, only needed for decoupled frontend development)

---

## 💾 Database Setup

WSMS stores configuration models and latency logs in a PostgreSQL database named `wsms`.

1. Open your PostgreSQL terminal (psql) or GUI client (like pgAdmin or DBeaver).
2. Create the target database:
   ```sql
   CREATE DATABASE wsms;
   ```
3. The system defaults to standard local credentials:
   - **Host**: `localhost:5432`
   - **Database**: `wsms`
   - **Username**: `postgres`
   - **Password**: `postgres`
   
   *(If your local PostgreSQL credentials differ, you can edit them instantly inside `backend/src/main/resources/application.yml` under the `spring.datasource` path).*

---

## 🚀 Running WSMS

### Option A: Unified Execution (Frictionless / Single Command)
This option serves the Vue 3 dashboard directly from Spring Boot. You do not need to install any Node.js dependencies!

1. Open a terminal in the `backend/` directory:
   ```bash
   cd backend
   ```
2. Build the project and download Maven dependencies:
   ```bash
   mvn clean install
   ```
3. Run the Spring Boot application:
   ```bash
   mvn spring-boot:run
   ```
4. Open your browser and navigate to:
   ```text
   http://localhost:8081/
   ```

---

### Option B: Decoupled Development Mode (Vite + Spring Boot)
This option is best if you want to modify the frontend code and enjoy instant Hot Module Replacement (HMR).

1. **Start the Backend API**:
   - Open a terminal in the `backend/` directory and run:
     ```bash
     mvn spring-boot:run
     ```
   - The API will boot up on `http://localhost:8081`.

2. **Start the Frontend Dev Server**:
   - Open a separate terminal in the `frontend/` directory.
   - Install dependencies:
     ```bash
     npm install
     ```
   - Launch Vite:
     ```bash
     npm run dev
     ```
   - Vite will boot up the hot-reloading dashboard on:
      ```text
      http://localhost:5173/
      ```
      *(Vite is pre-configured in `vite.config.js` to automatically proxy all `/api` requests to port `8081` without CORS hurdles).* 

### Frontend Branding & Logo

- The local frontend includes a lightweight SVG logo at `frontend/src/assets/logo.svg`. The dev server (Vite) displays this logo in the page header for a friendly branded experience.

Quick commands to run (from workspace root):

```powershell
cd "c:\Web Site Monitoring system\frontend"
npm install
npm run dev      # open http://localhost:5173/

# To build and copy into backend static (unified mode):
npm run build
mkdir -Force "..\backend\src\main\resources\static"
cp -Recurse dist\* "..\backend\src\main\resources\static\"
cd "..\backend"
..\apache-maven-3.9.10\bin\mvn.cmd spring-boot:run
```

The header and logo are intentionally simple and SVG-based so they scale crisply in the UI.

---

## 🧠 System Architecture & Algorithms

### 1. Performance Smoothing (EWMA)
To prevent normal internet jitters from generating misleading dashboard spikes, WSMS implements the **Exponentially Weighted Moving Average (EWMA)** algorithm:

$$EWMA_{new} = \alpha \cdot ResponseTime_{latest} + (1 - \alpha) \cdot EWMA_{previous}$$

* **$\alpha$ (Smoothing Factor)** is set to `0.3`.
* This ensures that if a website becomes permanently slower, the average smoothly catches up, but a single transient routing delay of 15 seconds will not trigger false panic on the operator panels.

### 2. Uptime & Retry Logic
When a background scheduled probe cycle fails:
1. The orchestrator isolates the target.
2. It launches an independent background thread which runs **3 rapid retries** spaced out by 8 seconds.
3. Only if **all 3 retries fail**, is the website marked as `DOWN` on the dashboard, and a time-series outage log is committed to PostgreSQL.
4. This suppresses alerts during quick, temporary gateway fluctuations.

### 3. SSL Expiry Checker
For any endpoint URL starting with `https://`, the background worker creates a raw socket connection on port `443`, triggers a secure handshake, and programmatically extracts the x509 certificate chain.
WSMS then parses the certificate's `getNotAfter()` validity timestamp and counts down remaining validity days, color-coding warnings in orange or blinking red when certificates are nearing expiration (less than 14 days).

---

## 📈 Verifying & Testing the Installation

1. **Add Websites**: Open the dashboard, click "+ Add Website", and register valid sites (e.g., `https://google.com`, `https://github.com`).
2. **Force Refresh**: Rather than waiting for the 60-second cron cycle, click the "Check Now" button (swirling arrows icon) to force an immediate probe.
3. **Simulate Outages**: Add a mock testing URL that deliberately throws errors (e.g., `https://httpbin.org/status/500` or `https://httpbin.org/delay/10` to trigger timeouts) and observe the logs.
   - Inspect console logs: you will see the system log retry sleeping loops:
     `WSMS-Prober-X - Sleeping 8000ms before retry attempt 2 for...`
   - After the 3rd retry, the dashboard will update the status badge to a pulsing red **DOWN** state, documenting the exact failure status code or network timeout.
4. **SSL Expiration Inspection**: Add a valid HTTPS URL and verify that the expiration date displays correctly. Add an invalid or local URL (`http://...`) and verify it gracefully handles "No SSL / Unverified" states without breaking.
