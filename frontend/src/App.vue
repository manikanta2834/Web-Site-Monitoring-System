<script setup>
import { ref, computed, onMounted, nextTick } from 'vue';
import axios from 'axios';
import Chart from 'chart.js/auto';

// Configure Axios Base URL dynamically for decoupled running (port 5173 to port 8081)
axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081';

// Reactivity States
const websites = ref([]);
const loading = ref(false);
const actionLoading = ref(null);
const formSubmitting = ref(false);
const selectedWebsite = ref(null);
const addModalOpen = ref(false);
const newWebsite = ref({
  name: '',
  url: '',
  interval: 60
});

// Alert notification state
const alert = ref({
  type: 'success',
  message: ''
});

// Chart instance
let chartInstance = null;

// Aggregated Stats Computations
const upCount = computed(() => websites.value.filter(s => s.status === 'UP').length);
const downCount = computed(() => websites.value.filter(s => s.status === 'DOWN').length);

const averageUptime = computed(() => {
  if (websites.value.length === 0) return 100;
  const total = websites.value.reduce((acc, curr) => acc + curr.uptimePercentage, 0);
  return Math.round((total / websites.value.length) * 10) / 10;
});

// Alert Banner Controls
const showAlert = (type, message) => {
  alert.value = { type, message };
  setTimeout(() => {
    if (alert.value.message === message) {
      dismissAlert();
    }
  }, 6000);
};

const dismissAlert = () => {
  alert.value.message = '';
};

// Fetch websites configurations
const fetchWebsites = async (silently = false) => {
  if (!silently) loading.value = true;
  try {
    const response = await axios.get('/api/websites');
    websites.value = response.data;

    // Keep detail panel updated
    if (selectedWebsite.value) {
      const updated = websites.value.find(s => s.id === selectedWebsite.value.id);
      if (updated) {
        selectedWebsite.value = updated;
        nextTick(() => {
          renderLatencyChart(updated);
        });
      }
    }
  } catch (error) {
    console.error(error);
    showAlert('error', 'Could not sync monitoring endpoints with the backend. Confirm the API port (8081) is running.');
  } finally {
    loading.value = false;
  }
};

// Create a Website Endpoint
const addWebsite = async () => {
  formSubmitting.value = true;
  try {
    const payload = {
      websiteName: newWebsite.value.name,
      websiteUrl: newWebsite.value.url,
      checkInterval: newWebsite.value.interval
    };
    await axios.post('/api/websites', payload);
    
    closeAddModal();
    showAlert('success', `Endpoint "${payload.websiteName}" has been successfully added to prober queues.`);
    
    await fetchWebsites();
    const newlyCreated = websites.value.find(s => s.websiteUrl === payload.websiteUrl);
    if (newlyCreated) {
      selectWebsite(newlyCreated);
    }
  } catch (error) {
    const msg = error.response?.data?.message || error.message;
    showAlert('error', `Failed to create website: ${msg}`);
  } finally {
    formSubmitting.value = false;
  }
};

// Delete Website
const deleteWebsite = async (id) => {
  if (!confirm("Are you sure you want to stop monitoring this website and delete all historical logs?")) {
    return;
  }
  try {
    await axios.delete(`/api/websites/${id}`);
    showAlert('success', 'Endpoint deregistered.');
    if (selectedWebsite.value && selectedWebsite.value.id === id) {
      selectedWebsite.value = null;
      if (chartInstance) {
        chartInstance.destroy();
        chartInstance = null;
      }
    }
    fetchWebsites();
  } catch (error) {
    showAlert('error', `Delete operation failed: ${error.response?.data?.message || error.message}`);
  }
};

// Force a probe check instantly
const manualCheck = async (id) => {
  actionLoading.value = id;
  try {
    await axios.post(`/api/websites/${id}/check`);
    showAlert('success', 'Immediate probe triggered.');
    setTimeout(() => {
      fetchWebsites(true);
      actionLoading.value = null;
    }, 2500);
  } catch (error) {
    showAlert('error', `Preflight check could not trigger: ${error.message}`);
    actionLoading.value = null;
  }
};

// Select a website
const selectWebsite = (website) => {
  selectedWebsite.value = website;
  nextTick(() => {
    renderLatencyChart(website);
  });
};

// Render historical latency charts
const renderLatencyChart = (website) => {
  const ctxElement = document.getElementById('latencyChart');
  if (!ctxElement) return;

  if (!website.recentLogs || website.recentLogs.length === 0) {
    if (chartInstance) {
      chartInstance.destroy();
      chartInstance = null;
    }
    return;
  }

  const sortedLogs = [...website.recentLogs].reverse();
  const labels = sortedLogs.map(l => {
    const date = new Date(l.checkedAt);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  });
  const latencies = sortedLogs.map(l => l.responseTime);
  const statuses = sortedLogs.map(l => l.status);

  if (chartInstance) {
    chartInstance.destroy();
  }

  const ctx = ctxElement.getContext('2d');
  const gradient = ctx.createLinearGradient(0, 0, 0, 200);
  gradient.addColorStop(0, 'rgba(139, 92, 246, 0.45)');
  gradient.addColorStop(1, 'rgba(139, 92, 246, 0.01)');

  chartInstance = new Chart(ctx, {
    type: 'line',
    data: {
      labels: labels,
      datasets: [{
        label: 'Latency (ms)',
        data: latencies,
        fill: true,
        backgroundColor: gradient,
        borderColor: '#a78bfa',
        borderWidth: 2,
        pointBackgroundColor: sortedLogs.map(l => l.status === 'UP' ? '#34d399' : '#f87171'),
        pointBorderColor: '#090d16',
        pointRadius: 4,
        pointHoverRadius: 6,
        tension: 0.35
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: {
          backgroundColor: 'rgba(9, 13, 22, 0.95)',
          titleFont: { family: 'Outfit', size: 11 },
          bodyFont: { family: 'Outfit', size: 12 },
          borderColor: 'rgba(255, 255, 255, 0.1)',
          borderWidth: 1,
          padding: 10,
          callbacks: {
            label: function(context) {
              const index = context.dataIndex;
              const status = statuses[index];
              return ` Latency: ${context.parsed.y} ms | ${status}`;
            }
          }
        }
      },
      scales: {
        y: {
          grid: { color: 'rgba(255, 255, 255, 0.04)' },
          ticks: {
            color: '#9ca3af',
            font: { family: 'Outfit', size: 10 },
            callback: value => value + 'ms'
          }
        },
        x: {
          grid: { display: false },
          ticks: {
            color: '#9ca3af',
            font: { family: 'Outfit', size: 9 },
            maxRotation: 45,
            minRotation: 45
          }
        }
      }
    }
  });
};

// Date Formatter
const formatTime = (isoString) => {
  if (!isoString) return 'Never checked';
  const date = new Date(isoString);
  return date.toLocaleString();
};

// SSL Expiry Calculations
const getSslDaysRemaining = (isoString) => {
  if (!isoString) return null;
  const expiry = new Date(isoString);
  const now = new Date();
  const diffTime = expiry - now;
  return Math.ceil(diffTime / (1000 * 60 * 60 * 24));
};

const getSslText = (isoString) => {
  const days = getSslDaysRemaining(isoString);
  if (days === null) return 'No SSL / Unverified';
  if (days <= 0) return 'Expired!';
  return `Valid (${days} days)`;
};

const formatSslDate = (isoString) => {
  if (!isoString) return 'Unavailable';
  const date = new Date(isoString);
  const days = getSslDaysRemaining(isoString);
  let suffix = `(Expires: ${date.toLocaleDateString()})`;
  if (days === null) return 'Unavailable';
  if (days <= 0) return `Expired! ${suffix}`;
  return `Expires in ${days} days ${suffix}`;
};

const getSslColorClass = (isoString) => {
  const days = getSslDaysRemaining(isoString);
  if (days === null) return 'text-gray-400';
  if (days <= 0) return 'text-rose-400 font-semibold animate-pulse';
  if (days < 14) return 'text-amber-400 font-semibold';
  return 'text-emerald-400';
};

// Modals
const openAddModal = () => {
  newWebsite.value = { name: '', url: '', interval: 60 };
  addModalOpen.value = true;
};

const closeAddModal = () => {
  addModalOpen.value = false;
};

// Poll on mount
onMounted(() => {
  fetchWebsites();
  setInterval(() => {
    fetchWebsites(true);
  }, 10000);
});
</script>

<template>
  <div class="relative z-10 p-4 md:p-8 max-w-7xl mx-auto">
    <!-- Background ambient elements -->
    <div class="absolute top-[-10%] left-[-10%] w-[50%] h-[50%] glow-orb-1 rounded-full pointer-events-none z-0"></div>
    <div class="absolute bottom-[-10%] right-[-10%] w-[50%] h-[50%] glow-orb-2 rounded-full pointer-events-none z-0"></div>

    <!-- Header -->
    <header class="flex flex-col md:flex-row md:items-center md:justify-between mb-8 pb-6 border-b border-white/5 gap-4 relative z-10">
      <div>
        <div class="flex items-center gap-3">
          <div class="bg-gradient-to-tr from-violet-600 to-indigo-600 p-2.5 rounded-xl shadow-lg shadow-violet-900/30">
            <i class="fa-solid fa-tower-broadcast text-xl text-white"></i>
          </div>
          <div>
            <h1 class="text-2xl font-bold tracking-tight text-white flex items-center gap-2">
              WSMS <span class="text-xs bg-violet-600/30 text-violet-400 font-medium px-2 py-0.5 rounded-full border border-violet-500/20">Standalone</span>
            </h1>
            <p class="text-xs text-gray-400">Decoupled Web Monitoring UI Console</p>
          </div>
        </div>
      </div>

      <div class="flex items-center gap-3 self-end md:self-auto">
        <span class="text-xs text-gray-400 flex items-center gap-1.5 bg-white/5 border border-white/5 px-3 py-1.5 rounded-lg">
          <i class="fa-solid fa-arrows-spin animate-spin text-violet-400" v-if="loading"></i>
          <i class="fa-solid fa-clock text-gray-400" v-else></i>
          Auto-refresh: 10s
        </span>
        <button @click="openAddModal" 
                class="bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white text-sm font-semibold px-4 py-2 rounded-lg shadow-lg shadow-indigo-600/20 flex items-center gap-2 transition duration-200">
          <i class="fa-solid fa-plus text-xs"></i> Add Website
        </button>
      </div>
    </header>

    <!-- Notification Banner -->
    <div v-if="alert.message" 
         :class="[
             'mb-6 p-4 rounded-xl flex items-start gap-3 border shadow-lg transition-all duration-300 relative z-10',
             alert.type === 'success' ? 'bg-emerald-950/40 border-emerald-500/30 text-emerald-300' : 'bg-rose-950/40 border-rose-500/30 text-rose-300'
         ]">
      <i class="fa-solid" :class="alert.type === 'success' ? 'fa-circle-check mt-0.5' : 'fa-circle-exclamation mt-0.5'"></i>
      <div class="flex-1 text-sm">
        <p class="font-medium">{{ alert.type === 'success' ? 'Success' : 'Error' }}</p>
        <p class="text-xs opacity-90 mt-0.5">{{ alert.message }}</p>
      </div>
      <button @click="dismissAlert" class="opacity-60 hover:opacity-100 text-sm">
        <i class="fa-solid fa-xmark"></i>
      </button>
    </div>

    <!-- Stats summary grid -->
    <section class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8 relative z-10">
      <div class="glass-card rounded-2xl p-4 flex items-center gap-4">
        <div class="bg-blue-600/10 border border-blue-500/20 p-3 rounded-xl">
          <i class="fa-solid fa-globe text-lg text-blue-400"></i>
        </div>
        <div>
          <p class="text-xs text-gray-400">Monitored Sites</p>
          <h3 class="text-xl font-bold mt-0.5">{{ websites.length }} / 10</h3>
        </div>
      </div>

      <div class="glass-card rounded-2xl p-4 flex items-center gap-4">
        <div class="bg-emerald-600/10 border border-emerald-500/20 p-3 rounded-xl">
          <i class="fa-solid fa-circle-up text-lg text-emerald-400"></i>
        </div>
        <div>
          <p class="text-xs text-gray-400">Active UP</p>
          <h3 class="text-xl font-bold mt-0.5 text-emerald-400">{{ upCount }}</h3>
        </div>
      </div>

      <div class="glass-card rounded-2xl p-4 flex items-center gap-4">
        <div class="bg-rose-600/10 border border-rose-500/20 p-3 rounded-xl">
          <i class="fa-solid fa-circle-down text-lg text-rose-400"></i>
        </div>
        <div>
          <p class="text-xs text-gray-400">Outages (DOWN)</p>
          <h3 class="text-xl font-bold mt-0.5" :class="downCount > 0 ? 'text-rose-400' : 'text-gray-300'">{{ downCount }}</h3>
        </div>
      </div>

      <div class="glass-card rounded-2xl p-4 flex items-center gap-4">
        <div class="bg-amber-600/10 border border-amber-500/20 p-3 rounded-xl">
          <i class="fa-solid fa-gauge-high text-lg text-amber-400"></i>
        </div>
        <div>
          <p class="text-xs text-gray-400">Avg Uptime SLA</p>
          <h3 class="text-xl font-bold mt-0.5 text-amber-400">{{ averageUptime }}%</h3>
        </div>
      </div>
    </section>

    <!-- Main Dashboard Split Pane -->
    <main class="grid grid-cols-1 lg:grid-cols-12 gap-8 relative z-10">
      <!-- Left Side List (7 Cols) -->
      <div class="lg:col-span-7 flex flex-col gap-4">
        <div class="flex items-center justify-between mb-2">
          <h2 class="text-lg font-semibold text-white">Endpoints Status Ledger</h2>
          <span class="text-xs text-gray-400">{{ websites.length }} website(s) active</span>
        </div>

        <!-- Empty State -->
        <div v-if="websites.length === 0" class="glass-card rounded-2xl p-12 text-center flex flex-col items-center justify-center">
          <div class="w-16 h-16 bg-white/5 border border-white/5 flex items-center justify-center rounded-2xl mb-4 text-violet-400">
            <i class="fa-solid fa-ban text-2xl"></i>
          </div>
          <h3 class="text-base font-semibold text-white">No Monitored Websites</h3>
          <p class="text-xs text-gray-400 max-w-sm mt-1 mb-6">Start tracking your digital perimeter by registering your first website endpoint url. Support up to 10 active targets.</p>
          <button @click="openAddModal" class="bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold px-4 py-2 rounded-lg shadow-md shadow-violet-900/20">
            Add First Website
          </button>
        </div>

        <!-- Cards -->
        <div v-for="site in websites" 
             :key="site.id" 
             @click="selectWebsite(site)"
             :class="[
                 'glass-card rounded-2xl p-5 cursor-pointer border-l-4 transition-all duration-200 hover:-translate-y-0.5',
                 selectedWebsite && selectedWebsite.id === site.id ? 'border-violet-500 bg-white/[0.04]' : 'hover:bg-white/[0.02]',
                 site.status === 'UP' ? 'border-l-emerald-500' : site.status === 'DOWN' ? 'border-l-rose-500' : 'border-l-amber-500'
             ]">
          <div class="flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-1.5 flex-wrap">
                <h4 class="font-bold text-white text-base truncate">{{ site.websiteName }}</h4>
                <span :class="[
                    'text-[10px] font-semibold px-2 py-0.5 rounded-full flex items-center gap-1.5 uppercase',
                    site.status === 'UP' ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20' : 
                    site.status === 'DOWN' ? 'bg-rose-500/10 text-rose-400 border border-rose-500/20' : 
                    'bg-amber-500/10 text-amber-400 border border-amber-500/20'
                ]">
                  <span :class="[
                      'w-1.5 h-1.5 rounded-full',
                      site.status === 'UP' ? 'bg-emerald-400 pulse-green' : 
                      site.status === 'DOWN' ? 'bg-rose-400 pulse-red' : 
                      'bg-amber-400 animate-pulse'
                  ]"></span>
                  {{ site.status }}
                </span>
              </div>
              <p class="text-xs text-gray-400 truncate flex items-center gap-1">
                <i class="fa-solid fa-link text-[10px]"></i>
                <a :href="site.websiteUrl" target="_blank" class="hover:text-violet-400 hover:underline" @click.stop>{{ site.websiteUrl }}</a>
              </p>
            </div>

            <!-- Stats -->
            <div class="flex items-center gap-6 justify-between md:justify-end text-xs">
              <div class="text-right">
                <p class="text-gray-400 text-[10px]">EWMA Latency</p>
                <p class="font-semibold text-gray-100 text-sm mt-0.5">
                  {{ Math.round(site.ewmaResponseTime) || 0 }}<span class="text-[10px] text-gray-400 ml-0.5">ms</span>
                </p>
              </div>
              
              <div class="text-right">
                <p class="text-gray-400 text-[10px]">Uptime SLA</p>
                <p class="font-semibold text-emerald-400 text-sm mt-0.5">{{ site.uptimePercentage }}%</p>
              </div>

              <div class="text-right w-20 hidden md:block">
                <p class="text-gray-400 text-[10px]">SSL Status</p>
                <span :class="['text-[10px] font-medium block mt-1', getSslColorClass(site.sslExpiryDate)]">
                  {{ getSslText(site.sslExpiryDate) }}
                </span>
              </div>
            </div>

            <!-- Action buttons -->
            <div class="flex items-center gap-2 border-t border-white/5 pt-3 md:pt-0 md:border-0 justify-end">
              <button @click.stop="manualCheck(site.id)" 
                      title="Check Now"
                      class="bg-white/5 hover:bg-white/10 text-gray-400 hover:text-white p-2 rounded-lg border border-white/5 transition duration-150 relative">
                <i class="fa-solid fa-arrows-rotate" :class="{'animate-spin': actionLoading === site.id}"></i>
              </button>
              <button @click.stop="deleteWebsite(site.id)" 
                      title="Delete Website"
                      class="bg-rose-950/20 hover:bg-rose-950/40 text-rose-400 hover:text-rose-300 p-2 rounded-lg border border-rose-950/30 transition duration-150">
                <i class="fa-solid fa-trash-can"></i>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Right Side details & charts (5 Cols) -->
      <div class="lg:col-span-5 flex flex-col gap-6">
        <h2 class="text-lg font-semibold text-white">Telemetry & Trend Analytics</h2>

        <div class="glass-card rounded-2xl p-6 relative min-h-[400px] flex flex-col justify-between">
          <!-- Unselected state -->
          <div v-if="!selectedWebsite" class="absolute inset-0 flex flex-col items-center justify-center p-6 text-center">
            <div class="w-14 h-14 bg-white/5 border border-white/5 flex items-center justify-center rounded-full mb-3 text-violet-400 animate-pulse">
              <i class="fa-solid fa-chart-line text-xl"></i>
            </div>
            <p class="text-sm font-medium text-white">Select a website to plot trends</p>
            <p class="text-xs text-gray-400 max-w-xs mt-1">Telemetry time-series analysis charts, status counts, and SSL validity details will render here in real-time.</p>
          </div>

          <!-- Selected State -->
          <div v-else class="w-full h-full flex flex-col justify-between gap-6">
            <div>
              <h3 class="text-lg font-bold text-white tracking-tight">{{ selectedWebsite.websiteName }}</h3>
              <p class="text-xs text-violet-400 truncate mb-4">{{ selectedWebsite.websiteUrl }}</p>
              
              <div class="grid grid-cols-2 gap-3 bg-white/[0.02] border border-white/5 p-4 rounded-xl text-xs mb-4">
                <div>
                  <span class="text-gray-400 block text-[10px]">Latest Latency</span>
                  <span class="font-bold text-white text-sm">{{ selectedWebsite.responseTime }} ms</span>
                </div>
                <div>
                  <span class="text-gray-400 block text-[10px]">Last Checked</span>
                  <span class="font-medium text-gray-200">{{ formatTime(selectedWebsite.lastCheckedTime) }}</span>
                </div>
                <div>
                  <span class="text-gray-400 block text-[10px]">Uptime Checks</span>
                  <span class="font-medium text-gray-200">{{ selectedWebsite.recentLogs.length }} historical checks</span>
                </div>
                <div>
                  <span class="text-gray-400 block text-[10px]">SSL Certificate Validity</span>
                  <span :class="['font-medium', getSslColorClass(selectedWebsite.sslExpiryDate)]">
                    {{ formatSslDate(selectedWebsite.sslExpiryDate) }}
                  </span>
                </div>
              </div>
            </div>

            <!-- Latency Chart Pane -->
            <div class="flex-1 w-full min-h-[200px] flex items-center justify-center relative mb-4">
              <div v-if="!selectedWebsite.recentLogs || !selectedWebsite.recentLogs.length" class="text-center">
                <i class="fa-solid fa-hourglass-half text-amber-400 animate-pulse text-lg mb-2"></i>
                <p class="text-xs text-gray-400">Waiting for logs to accumulate...</p>
              </div>
              <canvas id="latencyChart" class="w-full h-full max-h-[220px]" v-show="selectedWebsite.recentLogs && selectedWebsite.recentLogs.length"></canvas>
            </div>

            <div class="text-[10px] text-gray-400 border-t border-white/5 pt-4 flex items-center justify-between">
              <span>Check Interval: every {{ selectedWebsite.checkInterval }} seconds</span>
              <span class="flex items-center gap-1"><i class="fa-solid fa-shield-halved text-emerald-400"></i> Active Protection Enabled</span>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- ADD WEBSITE MODAL (Glassmorphic) -->
    <div v-if="addModalOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm transition-all duration-300">
      <div class="glass-card w-full max-w-md rounded-2xl p-6 shadow-2xl relative border border-white/10" @click.stop>
        <header class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-white flex items-center gap-2">
            <i class="fa-solid fa-circle-plus text-violet-400"></i> Add Endpoint configuration
          </h3>
          <button @click="closeAddModal" class="text-gray-400 hover:text-white transition">
            <i class="fa-solid fa-xmark text-lg"></i>
          </button>
        </header>

        <form @submit.prevent="addWebsite">
          <div class="flex flex-col gap-4 text-sm mb-6">
            <div>
              <label for="name" class="block text-xs font-semibold text-gray-300 mb-1.5">Website Name</label>
              <input type="text" id="name" required v-model="newWebsite.name" placeholder="e.g. Google Search" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>

            <div>
              <label for="url" class="block text-xs font-semibold text-gray-300 mb-1.5">Website Endpoint URL</label>
              <input type="url" id="url" required v-model="newWebsite.url" placeholder="https://example.com" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
              <span class="text-[10px] text-gray-400 mt-1 block">Must start with http:// or https://</span>
            </div>

            <div>
              <label for="interval" class="block text-xs font-semibold text-gray-300 mb-1.5">Check Interval (Seconds)</label>
              <select id="interval" v-model="newWebsite.interval" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-300 appearance-none bg-indigo-950/30">
                <option :value="30">30 seconds (Aggressive)</option>
                <option :value="60">60 seconds (Standard)</option>
                <option :value="300">5 minutes (Periodic)</option>
              </select>
            </div>
          </div>

          <footer class="flex items-center justify-end gap-3">
            <button type="button" @click="closeAddModal" class="px-4 py-2 border border-white/5 bg-white/5 hover:bg-white/10 text-gray-300 rounded-lg text-xs font-semibold transition duration-150">Cancel</button>
            <button type="submit" :disabled="formSubmitting" class="px-4 py-2 bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white rounded-lg text-xs font-semibold shadow-md shadow-violet-950/25 flex items-center gap-2 transition duration-150">
              <i class="fa-solid fa-arrows-spin animate-spin" v-if="formSubmitting"></i>
              Register Endpoint
            </button>
          </footer>
        </form>
      </div>
    </div>
  </div>
</template>
