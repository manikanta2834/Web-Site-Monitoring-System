<script setup>
import { ref, computed, onMounted, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import apiClient from '../api/client';
import { useAuthStore } from '../store/auth';
import Chart from 'chart.js/auto';
import ChatAssistant from '../components/ChatAssistant.vue';

const router = useRouter();
const authStore = useAuthStore();

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
  interval: 60,
  sslExpiryThreshold: 30,
  dnsLookupThreshold: 150,
  ewmaThreshold: 500
});

const editModalOpen = ref(false);
const editingWebsite = ref({
  id: null,
  name: '',
  url: '',
  interval: 60,
  sslExpiryThreshold: 30,
  dnsLookupThreshold: 150,
  ewmaThreshold: 500
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

const averageDnsTime = computed(() => {
  if (websites.value.length === 0) return 0;
  const total = websites.value.reduce((acc, curr) => acc + (curr.dnsLookupTime || 0), 0);
  return Math.round((total / websites.value.length) * 100) / 100;
});

// Sort websites: DOWN domains first, then UP, then alphabetical by name
const sortedWebsites = computed(() => {
  return [...websites.value].sort((a, b) => {
    if (a.status === 'DOWN' && b.status !== 'DOWN') return -1;
    if (a.status !== 'DOWN' && b.status === 'DOWN') return 1;
    if (a.status === 'UP' && b.status !== 'UP') return -1;
    if (a.status !== 'UP' && b.status === 'UP') return 1;
    return a.websiteName.localeCompare(b.websiteName);
  });
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

// Caches to track status changes and prevent repeat alert/voice spam
const previousStatuses = new Map();
const previousBreaches = new Map();

// Fetch websites configurations
const fetchWebsites = async (silently = false) => {
  if (!silently) loading.value = true;
  try {
    const response = await apiClient.get('/api/websites');
    const fetchedData = response.data;

    // Detect status changes and trigger transition alerts
    fetchedData.forEach(site => {
      const previousStatus = previousStatuses.get(site.id);
      const isBreached = isThresholdBreached(site);
      const wasBreached = previousBreaches.get(site.id) || false;
      
      if (previousStatus) {
        // Outage Alert
        if (previousStatus !== 'DOWN' && site.status === 'DOWN') {
          showAlert('error', `🚨 OUTAGE ALERT: Website "${site.websiteName}" (${site.websiteUrl}) is DOWN!`);
          speakAlert(`Warning. Website ${site.websiteName} is down. Please check immediately.`);
          
          if (typeof window !== 'undefined' && 'Notification' in window && Notification.permission === 'granted') {
            new Notification('WSMS Outage Alert', {
              body: `Website "${site.websiteName}" is DOWN!`,
              icon: '/src/assets/logo.svg'
            });
          }
        }
        // Recovery Alert
        else if (previousStatus === 'DOWN' && site.status === 'UP') {
          showAlert('success', `✅ RECOVERY ALERT: Website "${site.websiteName}" is back UP!`);
          speakAlert(`Alert resolved. Website ${site.websiteName} is back online.`);
        }
        
        // Threshold Warnings (only if UP)
        if (site.status === 'UP') {
          if (isBreached && !wasBreached) {
            let reason = "";
            if ((site.ewmaResponseTime || 0) > (site.ewmaThreshold || 500)) {
              reason += "high response latency, ";
            }
            if ((site.dnsLookupTime || 0) > (site.dnsLookupThreshold || 150)) {
              reason += "slow DNS lookup time, ";
            }
            if (site.protocol === 'HTTPS' && site.sslExpiryDate) {
              const days = getSslDaysRemaining(site.sslExpiryDate);
              if (days !== null && days < (site.sslExpiryThreshold || 30)) {
                reason += "upcoming SSL certificate expiration, ";
              }
            }
            if (reason.endsWith(", ")) {
              reason = reason.substring(0, reason.length - 2);
            }
            
            showAlert('error', `⚠️ THRESHOLD BREACH: Website "${site.websiteName}" exceeds warning limits!`);
            speakAlert(`Attention. Website ${site.websiteName} has breached warning thresholds due to ${reason}.`);
          }
          else if (!isBreached && wasBreached) {
            showAlert('success', `✨ THRESHOLD CLEARED: Website "${site.websiteName}" metrics returned to normal.`);
            speakAlert(`Threshold alert cleared. Website ${site.websiteName} metrics are back within normal parameters.`);
          }
        }
      }
      
      // Update caches
      previousStatuses.set(site.id, site.status);
      previousBreaches.set(site.id, isBreached);
    });

    websites.value = fetchedData;

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
      checkInterval: newWebsite.value.interval,
      sslExpiryThreshold: newWebsite.value.sslExpiryThreshold,
      dnsLookupThreshold: newWebsite.value.dnsLookupThreshold,
      ewmaThreshold: newWebsite.value.ewmaThreshold
    };
    await apiClient.post('/api/websites', payload);
    
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

// Update an existing website
const updateWebsite = async () => {
  formSubmitting.value = true;
  try {
    const payload = {
      websiteName: editingWebsite.value.name,
      websiteUrl: editingWebsite.value.url,
      checkInterval: editingWebsite.value.interval,
      sslExpiryThreshold: editingWebsite.value.sslExpiryThreshold,
      dnsLookupThreshold: editingWebsite.value.dnsLookupThreshold,
      ewmaThreshold: editingWebsite.value.ewmaThreshold
    };
    await apiClient.put(`/api/websites/${editingWebsite.value.id}`, payload);
    
    closeEditModal();
    showAlert('success', `Endpoint "${payload.websiteName}" has been successfully updated.`);
    
    await fetchWebsites();
  } catch (error) {
    const msg = error.response?.data?.message || error.message;
    showAlert('error', `Failed to update website: ${msg}`);
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
    await apiClient.delete(`/api/websites/${id}`);
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
    await apiClient.post(`/api/websites/${id}/check`);
    showAlert('success', 'Immediate probe triggered.');
    // Fetch websites immediately so the PENDING status displays in the UI right away
    await fetchWebsites(true);
    setTimeout(() => {
      fetchWebsites(true);
      actionLoading.value = null;
    }, 2500);
  } catch (error) {
    const errorMsg = error.response?.data || error.message;
    showAlert('error', `Preflight check could not trigger: ${errorMsg}`);
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

// Threshold Warning Evaluator
const isThresholdBreached = (site) => {
  if (site.status === 'DOWN') return false;
  
  const ewmaBreach = (site.ewmaResponseTime || 0) > (site.ewmaThreshold || 500);
  const dnsBreach = (site.dnsLookupTime || 0) > (site.dnsLookupThreshold || 150);
  
  let sslBreach = false;
  if (site.protocol === 'HTTPS' && site.sslExpiryDate) {
    const days = getSslDaysRemaining(site.sslExpiryDate);
    if (days !== null && days < (site.sslExpiryThreshold || 30)) {
      sslBreach = true;
    }
  }
  return ewmaBreach || dnsBreach || sslBreach;
};

// Pre-load and listen to SpeechSynthesis voices loading asynchronously
let availableVoices = [];
const loadVoices = () => {
  if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
    availableVoices = window.speechSynthesis.getVoices();
  }
};
if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
  window.speechSynthesis.onvoiceschanged = loadVoices;
  loadVoices();
}

const isVoiceMuted = ref(localStorage.getItem('isVoiceMuted') === 'true');

const toggleVoiceMute = () => {
  isVoiceMuted.value = !isVoiceMuted.value;
  localStorage.setItem('isVoiceMuted', isVoiceMuted.value.toString());
  if (isVoiceMuted.value) {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
  }
};

// Speech Synthesis Alerts Helper (Sweet Female Voice)
const speakAlert = (text) => {
  if (isVoiceMuted.value) return;
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
  
  window.speechSynthesis.cancel(); // Clear speaking queue immediately
  const utterance = new SpeechSynthesisUtterance(text);
  
  let voices = window.speechSynthesis.getVoices();
  if (!voices || voices.length === 0) {
    voices = availableVoices;
  }
  
  let femaleVoice = voices.find(voice => {
    const name = voice.name.toLowerCase();
    const lang = voice.lang.toLowerCase();
    if (!lang.startsWith('en')) return false;
    return name.includes('zira') || 
           name.includes('samantha') || 
           name.includes('siri') || 
           name.includes('google us english') || 
           name.includes('natural') && name.includes('female') || 
           name.includes('female') || 
           name.includes('aria') || 
           name.includes('hazel') || 
           name.includes('susan');
  });

  if (!femaleVoice) {
    femaleVoice = voices.find(voice => {
      const name = voice.name.toLowerCase();
      const lang = voice.lang.toLowerCase();
      if (!lang.startsWith('en')) return false;
      return !name.includes('david') && 
             !name.includes('mark') && 
             !name.includes('ravi') && 
             !name.includes('george') && 
             !name.includes('male');
    });
  }

  if (femaleVoice) {
    utterance.voice = femaleVoice;
  }
  utterance.rate = 0.92;
  utterance.pitch = 1.15;
  window.speechSynthesis.speak(utterance);
};

// Modals
const openAddModal = () => {
  newWebsite.value = {
    name: '',
    url: '',
    interval: 60,
    sslExpiryThreshold: globalSettings.value.sslExpiryThreshold,
    dnsLookupThreshold: globalSettings.value.dnsLookupThreshold,
    ewmaThreshold: globalSettings.value.ewmaThreshold
  };
  addModalOpen.value = true;
};

const closeAddModal = () => {
  addModalOpen.value = false;
};

const openEditModal = (site) => {
  editingWebsite.value = {
    id: site.id,
    name: site.websiteName,
    url: site.websiteUrl,
    interval: site.checkInterval,
    sslExpiryThreshold: site.sslExpiryThreshold || 30,
    dnsLookupThreshold: site.dnsLookupThreshold || 150,
    ewmaThreshold: site.ewmaThreshold || 500
  };
  editModalOpen.value = true;
};

const closeEditModal = () => {
  editModalOpen.value = false;
};

// Logout handler
const handleLogout = async () => {
  try {
    const rToken = authStore.refreshToken.value;
    if (rToken) {
      await apiClient.post('/api/auth/logout', { refreshToken: rToken });
    }
  } catch (error) {
    console.error('Logout failed:', error);
  } finally {
    authStore.clearTokens();
    router.push({ name: 'Login' });
  }
};

const getCleanRole = (rolesList) => {
  if (!rolesList || rolesList.length === 0) return 'Guest';
  const role = rolesList[0];
  return role.replace('ROLE_', '');
};

// Settings Modal State
const settingsModalOpen = ref(false);
const globalSettings = ref({
  connectionTimeout: 5000,
  retryCount: 3,
  sslExpiryThreshold: 30,
  dnsLookupThreshold: 150.0,
  ewmaThreshold: 500.0
});
const settingsSubmitting = ref(false);

const loadGlobalSettings = async () => {
  try {
    const response = await apiClient.get('/api/settings');
    const list = response.data;
    list.forEach(item => {
      if (item.settingKey === 'global_connection_timeout') {
        globalSettings.value.connectionTimeout = parseInt(item.settingValue);
      } else if (item.settingKey === 'global_retry_count') {
        globalSettings.value.retryCount = parseInt(item.settingValue);
      } else if (item.settingKey === 'global_ssl_expiry_threshold') {
        globalSettings.value.sslExpiryThreshold = parseInt(item.settingValue);
      } else if (item.settingKey === 'global_dns_lookup_threshold') {
        globalSettings.value.dnsLookupThreshold = parseFloat(item.settingValue);
      } else if (item.settingKey === 'global_ewma_threshold') {
        globalSettings.value.ewmaThreshold = parseFloat(item.settingValue);
      }
    });
  } catch (error) {
    console.error('Failed to load global settings:', error);
  }
};

const openSettingsModal = async () => {
  await loadGlobalSettings();
  settingsModalOpen.value = true;
};

const closeSettingsModal = () => {
  settingsModalOpen.value = false;
};

const saveSettings = async () => {
  settingsSubmitting.value = true;
  try {
    const updatePromises = [
      apiClient.put('/api/settings/global_connection_timeout', { value: String(globalSettings.value.connectionTimeout) }),
      apiClient.put('/api/settings/global_retry_count', { value: String(globalSettings.value.retryCount) }),
      apiClient.put('/api/settings/global_ssl_expiry_threshold', { value: String(globalSettings.value.sslExpiryThreshold) }),
      apiClient.put('/api/settings/global_dns_lookup_threshold', { value: String(globalSettings.value.dnsLookupThreshold) }),
      apiClient.put('/api/settings/global_ewma_threshold', { value: String(globalSettings.value.ewmaThreshold) })
    ];
    await Promise.all(updatePromises);
    showAlert('success', 'Global threshold limits updated successfully.');
    closeSettingsModal();
  } catch (error) {
    showAlert('error', `Failed to save settings: ${error.response?.data?.message || error.message}`);
  } finally {
    settingsSubmitting.value = false;
  }
};

// Active Customization State
const activeCustomization = ref({
  name: 'Default Layout',
  headerText: 'WSMS',
  footerText: 'WSMS Standalone - Advanced Monitoring & Observability Platform',
  bodyContent: 'Observability Gateway & Observational Control',
  logoUrl: '/src/assets/logo.svg'
});

const fetchActiveCustomization = async () => {
  try {
    const response = await apiClient.get('/api/portal-customizations/active');
    if (response.data) {
      activeCustomization.value = response.data;
    }
  } catch (err) {
    console.error('Failed to load active customization:', err);
  }
};

// Layout management states
const customizationsList = ref([]);
const customizationModalOpen = ref(false);
const customizationFormSubmitting = ref(false);
const isEditingCustomization = ref(false);
const showCustomizationForm = ref(false);
const currentCustomizationId = ref(null);

const customizationForm = ref({
  name: '',
  headerText: '',
  footerText: '',
  bodyContent: '',
  logoUrl: ''
});

const openCustomizationModal = async () => {
  customizationModalOpen.value = true;
  await fetchCustomizations();
};

const closeCustomizationModal = () => {
  customizationModalOpen.value = false;
  resetCustomizationForm();
};

const cancelCustomizationForm = () => {
  resetCustomizationForm();
  showCustomizationForm.value = false;
};

const resetCustomizationForm = () => {
  customizationForm.value = {
    name: '',
    headerText: '',
    footerText: '',
    bodyContent: '',
    logoUrl: ''
  };
  isEditingCustomization.value = false;
  showCustomizationForm.value = false;
  currentCustomizationId.value = null;
};

const fetchCustomizations = async () => {
  try {
    const response = await apiClient.get('/api/portal-customizations');
    customizationsList.value = response.data;
  } catch (err) {
    console.error('Failed to load layout customizations list:', err);
    showAlert('error', 'Failed to retrieve layout configurations.');
  }
};

const handleCustomizationSubmit = async () => {
  customizationFormSubmitting.value = true;
  try {
    const payload = { ...customizationForm.value };
    if (isEditingCustomization.value) {
      await apiClient.put(`/api/portal-customizations/${currentCustomizationId.value}`, payload);
      showAlert('success', 'Layout configuration updated successfully.');
    } else {
      await apiClient.post('/api/portal-customizations', payload);
      showAlert('success', 'New layout configuration created.');
    }
    resetCustomizationForm();
    await fetchCustomizations();
  } catch (err) {
    console.error(err);
    const errorMsg = err.response?.data?.message || 'Failed to save layout configuration.';
    showAlert('error', errorMsg);
  } finally {
    customizationFormSubmitting.value = false;
  }
};

const editCustomization = (item) => {
  isEditingCustomization.value = true;
  currentCustomizationId.value = item.id;
  customizationForm.value = {
    name: item.name,
    headerText: item.headerText,
    footerText: item.footerText,
    bodyContent: item.bodyContent,
    logoUrl: item.logoUrl
  };
};

const activateCustomization = async (id) => {
  try {
    await apiClient.post(`/api/portal-customizations/${id}/activate`);
    showAlert('success', 'Layout configuration activated.');
    await fetchActiveCustomization();
    await fetchCustomizations();
  } catch (err) {
    console.error(err);
    showAlert('error', 'Failed to activate layout configuration.');
  }
};

const deleteCustomization = async (id) => {
  if (!confirm('Are you sure you want to delete this layout configuration?')) return;
  try {
    await apiClient.delete(`/api/portal-customizations/${id}`);
    showAlert('success', 'Layout configuration deleted.');
    await fetchCustomizations();
  } catch (err) {
    console.error(err);
    const errorMsg = err.response?.data?.message || 'Failed to delete layout configuration.';
    showAlert('error', errorMsg);
  }
};

// Poll on mount
onMounted(() => {
  fetchActiveCustomization();
  loadGlobalSettings();
  fetchWebsites();
  
  if (typeof window !== 'undefined' && 'Notification' in window && Notification.permission === 'default') {
    Notification.requestPermission();
  }
  
  const intervalId = setInterval(() => {
    fetchWebsites(true);
  }, 10000);

  return () => clearInterval(intervalId);
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
          <div class="bg-gradient-to-tr from-violet-600 to-indigo-600 p-2.5 rounded-xl shadow-lg shadow-violet-900/30 flex items-center justify-center w-12 h-12 overflow-hidden">
            <img v-if="activeCustomization.logoUrl && (activeCustomization.logoUrl.startsWith('/') || activeCustomization.logoUrl.startsWith('http'))" 
                 :src="activeCustomization.logoUrl" 
                 class="w-full h-full object-contain" 
                 alt="Logo" />
            <i v-else class="fa-solid fa-tower-broadcast text-xl text-white"></i>
          </div>
          <div>
            <h1 class="text-2xl font-bold tracking-tight text-white flex items-center gap-2">
              {{ activeCustomization.headerText }} <span class="text-xs bg-violet-600/30 text-violet-400 font-medium px-2 py-0.5 rounded-full border border-violet-500/20">Standalone</span>
            </h1>
            <p class="text-xs text-gray-400">{{ activeCustomization.bodyContent }}</p>
          </div>
        </div>
      </div>

      <!-- Logged in user info & controls -->
      <div class="flex flex-wrap items-center gap-3 self-start md:self-auto">
        <!-- User card -->
        <div class="flex items-center gap-2 bg-white/5 border border-white/5 px-3 py-1.5 rounded-lg text-xs text-gray-300">
          <div class="w-5 h-5 rounded-full bg-violet-600/20 border border-violet-500/30 flex items-center justify-center">
            <i class="fa-solid fa-user text-[10px] text-violet-400"></i>
          </div>
          <div class="text-left leading-none">
            <span class="font-semibold text-white block">{{ authStore.username.value }}</span>
            <span class="text-[9px] text-gray-400 font-medium block uppercase tracking-wider mt-0.5">{{ getCleanRole(authStore.roles.value) }}</span>
          </div>
        </div>

        <span class="text-xs text-gray-400 flex items-center gap-1.5 bg-white/5 border border-white/5 px-3 py-1.5 rounded-lg">
          <i class="fa-solid fa-arrows-spin animate-spin text-violet-400" v-if="loading"></i>
          <i class="fa-solid fa-clock text-gray-400" v-else></i>
          Auto-refresh: 10s
        </span>

        <!-- Operations add site button -->
        <button v-if="authStore.isOperator.value" 
                @click="openAddModal" 
                class="bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white text-sm font-semibold px-4 py-2 rounded-lg shadow-lg shadow-indigo-600/20 flex items-center gap-2 transition duration-200">
          <i class="fa-solid fa-plus text-xs"></i> Add Website
        </button>

        <!-- Global Settings button -->
        <button v-if="authStore.isOperator.value" 
                @click="openSettingsModal" 
                class="bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white text-sm font-semibold px-4 py-2 rounded-lg shadow-lg shadow-indigo-600/20 flex items-center gap-2 transition duration-200">
          <i class="fa-solid fa-sliders text-xs"></i> Global Thresholds
        </button>

        <!-- Portal Customization button -->
        <button v-if="authStore.isOperator.value" 
                @click="openCustomizationModal" 
                class="bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white text-sm font-semibold px-4 py-2 rounded-lg shadow-lg shadow-indigo-600/20 flex items-center gap-2 transition duration-200">
          <i class="fa-solid fa-palette text-xs"></i> Portal Layouts
        </button>

        <!-- Voice Assistant Mute Toggle -->
        <button @click="toggleVoiceMute"
                :title="isVoiceMuted ? 'Unmute Voice Assistant' : 'Mute Voice Assistant'"
                class="bg-white/5 border border-white/5 p-2 rounded-lg transition duration-200 flex items-center justify-center"
                :class="{'text-violet-400 border-violet-500/20 bg-violet-500/10 hover:bg-violet-500/20': !isVoiceMuted, 'text-gray-500 hover:text-rose-400 hover:bg-rose-950/20': isVoiceMuted}">
          <i class="fa-solid" :class="isVoiceMuted ? 'fa-volume-xmark' : 'fa-volume-high'"></i>
        </button>

        <!-- Logout Button -->
        <button @click="handleLogout"
                title="Sign Out"
                class="bg-white/5 hover:bg-rose-950/20 hover:text-rose-400 border border-white/5 text-gray-400 p-2 rounded-lg transition duration-200 flex items-center justify-center">
          <i class="fa-solid fa-right-from-bracket"></i>
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
    <section class="grid grid-cols-2 md:grid-cols-5 gap-4 mb-8 relative z-10">
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
        <div class="bg-violet-600/10 border border-violet-500/20 p-3 rounded-xl">
          <i class="fa-solid fa-server text-lg text-violet-400"></i>
        </div>
        <div>
          <p class="text-xs text-gray-400">Avg DNS Lookup</p>
          <h3 class="text-xl font-bold mt-0.5 text-violet-400">{{ averageDnsTime }}<span class="text-[10px] text-gray-400 ml-0.5">ms</span></h3>
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
          <button v-if="authStore.isOperator.value" @click="openAddModal" class="bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold px-4 py-2 rounded-lg shadow-md shadow-violet-900/20">
            Add First Website
          </button>
        </div>

        <!-- Cards -->
        <div v-for="site in sortedWebsites" 
             :key="site.id" 
             @click="selectWebsite(site)"
             :class="[
                 'glass-card rounded-2xl p-5 cursor-pointer border-l-4 transition-all duration-200 hover:-translate-y-0.5',
                 selectedWebsite && selectedWebsite.id === site.id ? 'border-violet-500 bg-white/[0.04]' : 'hover:bg-white/[0.02]',
                 site.status === 'DOWN' ? 'border-l-rose-500' : (isThresholdBreached(site) ? 'border-l-amber-500' : 'border-l-emerald-500')
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
                <span class="text-[10px] font-bold px-2 py-0.5 rounded-full bg-white/5 border border-white/10 text-gray-300 uppercase">
                  {{ site.protocol || 'HTTP' }}
                </span>
                <span v-if="isThresholdBreached(site)" class="text-[10px] font-bold px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-400 border border-amber-500/20 flex items-center gap-1 uppercase">
                  <i class="fa-solid fa-triangle-exclamation"></i> Warning
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
                <p class="text-gray-400 text-[10px]">DNS Lookup</p>
                <p class="font-semibold text-violet-400 text-sm mt-0.5">
                  {{ site.dnsLookupTime || 0.0 }}<span class="text-[10px] text-gray-400 ml-0.5">ms</span>
                </p>
              </div>

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

            <!-- Action buttons - protected based on roles -->
            <div class="flex items-center gap-2 border-t border-white/5 pt-3 md:pt-0 md:border-0 justify-end">
              <button v-if="authStore.isOperator.value" 
                      @click.stop="openEditModal(site)" 
                      title="Edit Website"
                      class="bg-white/5 hover:bg-white/10 text-gray-400 hover:text-white p-2 rounded-lg border border-white/5 transition duration-150">
                <i class="fa-solid fa-pen"></i>
              </button>
              <button v-if="authStore.isOperator.value" 
                      @click.stop="manualCheck(site.id)" 
                      title="Check Now"
                      class="bg-white/5 hover:bg-white/10 text-gray-400 hover:text-white p-2 rounded-lg border border-white/5 transition duration-150 relative">
                <i class="fa-solid fa-arrows-rotate" :class="{'animate-spin': actionLoading === site.id}"></i>
              </button>
              <button v-if="authStore.isAdmin.value" 
                      @click.stop="deleteWebsite(site.id)" 
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
                  <span class="text-gray-400 block text-[10px]">Protocol / Connection</span>
                  <span class="font-bold text-white text-sm flex items-center gap-1.5 mt-0.5">
                    <i :class="selectedWebsite.protocol === 'HTTPS' ? 'fa-solid fa-lock text-emerald-400' : 'fa-solid fa-unlock-keyhole text-amber-400'"></i>
                    {{ selectedWebsite.protocol || 'HTTP' }}
                  </span>
                </div>
                <div>
                  <span class="text-gray-400 block text-[10px]">DNS Lookup Time</span>
                  <span class="font-bold text-violet-400 text-sm mt-0.5">{{ selectedWebsite.dnsLookupTime || 0.0 }} ms</span>
                </div>
                <div>
                  <span class="text-gray-400 block text-[10px]">HTTP Probing Latency</span>
                  <span class="font-bold text-white text-sm mt-0.5">{{ selectedWebsite.responseTime }} ms</span>
                </div>
                <div>
                  <span class="text-gray-400 block text-[10px]">Last Checked</span>
                  <span class="font-medium text-gray-200 mt-0.5 block">{{ formatTime(selectedWebsite.lastCheckedTime) }}</span>
                </div>
                
                <!-- SSL certificate info (only if HTTPS) -->
                <div v-if="selectedWebsite.protocol === 'HTTPS'" class="col-span-2 border-t border-white/5 pt-2.5 mt-1 grid grid-cols-2 gap-3">
                  <div>
                    <span class="text-gray-400 block text-[10px]">SSL Issuer (Authority)</span>
                    <span class="font-bold text-gray-200 text-xs mt-0.5 block truncate" :title="selectedWebsite.sslIssuer">
                      <i class="fa-solid fa-building-shield text-violet-400 mr-1"></i>
                      {{ selectedWebsite.sslIssuer || 'Loading...' }}
                    </span>
                  </div>
                  <div>
                    <span class="text-gray-400 block text-[10px]">SSL Certificate Validity</span>
                    <span :class="['font-medium text-xs mt-0.5 block', getSslColorClass(selectedWebsite.sslExpiryDate)]">
                      {{ getSslText(selectedWebsite.sslExpiryDate) }}
                    </span>
                  </div>
                  <div class="col-span-2">
                    <span class="text-gray-400 block text-[10px]">Expiration & Time Remaining</span>
                    <span :class="['font-medium text-xs mt-0.5 block', getSslColorClass(selectedWebsite.sslExpiryDate)]">
                      <i class="fa-regular fa-clock mr-1"></i>
                      {{ formatSslDate(selectedWebsite.sslExpiryDate) }}
                    </span>
                  </div>
                </div>
                <!-- Non-SSL Info (if HTTP) -->
                <div v-else class="col-span-2 border-t border-white/5 pt-2.5 mt-1">
                  <span class="text-rose-400/80 text-[11px] flex items-center gap-1">
                    <i class="fa-solid fa-circle-exclamation"></i>
                    Unencrypted HTTP connection. No SSL/TLS Certificate present.
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

            <!-- Threshold Limits Config -->
            <div class="border-t border-white/5 pt-4 mt-2">
              <h4 class="text-xs font-bold text-violet-400 mb-3 flex items-center gap-1.5">
                <i class="fa-solid fa-triangle-exclamation"></i> Alert Threshold Warning Levels
              </h4>
              <div class="grid grid-cols-3 gap-3">
                <div>
                  <label for="ssl_threshold" class="block text-[10px] font-semibold text-gray-300 mb-1">SSL Expiry (Days)</label>
                  <input type="number" id="ssl_threshold" min="1" required v-model="newWebsite.sslExpiryThreshold" class="w-full glass-input px-2.5 py-1.5 rounded-lg text-gray-200 text-xs">
                </div>
                <div>
                  <label for="dns_threshold" class="block text-[10px] font-semibold text-gray-300 mb-1">DNS Lookup (ms)</label>
                  <input type="number" id="dns_threshold" min="1" required v-model="newWebsite.dnsLookupThreshold" class="w-full glass-input px-2.5 py-1.5 rounded-lg text-gray-200 text-xs">
                </div>
                <div>
                  <label for="ewma_threshold_input" class="block text-[10px] font-semibold text-gray-300 mb-1">EWMA Latency (ms)</label>
                  <input type="number" id="ewma_threshold_input" min="1" required v-model="newWebsite.ewmaThreshold" class="w-full glass-input px-2.5 py-1.5 rounded-lg text-gray-200 text-xs">
                </div>
              </div>
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

    <!-- EDIT WEBSITE MODAL (Glassmorphic) -->
    <div v-if="editModalOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm transition-all duration-300">
      <div class="glass-card w-full max-w-md rounded-2xl p-6 shadow-2xl relative border border-white/10" @click.stop>
        <header class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-white flex items-center gap-2">
            <i class="fa-solid fa-pen-to-square text-violet-400"></i> Edit Endpoint Configuration
          </h3>
          <button @click="closeEditModal" class="text-gray-400 hover:text-white transition">
            <i class="fa-solid fa-xmark text-lg"></i>
          </button>
        </header>

        <form @submit.prevent="updateWebsite">
          <div class="flex flex-col gap-4 text-sm mb-6">
            <div>
              <label for="edit_name" class="block text-xs font-semibold text-gray-300 mb-1.5">Website Name</label>
              <input type="text" id="edit_name" required v-model="editingWebsite.name" placeholder="e.g. Google Search" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>

            <div>
              <label for="edit_url" class="block text-xs font-semibold text-gray-300 mb-1.5">Website Endpoint URL</label>
              <input type="url" id="edit_url" required v-model="editingWebsite.url" placeholder="https://example.com" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
              <span class="text-[10px] text-gray-400 mt-1 block">Must start with http:// or https://</span>
            </div>

            <div>
              <label for="edit_interval" class="block text-xs font-semibold text-gray-300 mb-1.5">Check Interval (Seconds)</label>
              <select id="edit_interval" v-model="editingWebsite.interval" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-300 appearance-none bg-indigo-950/30">
                <option :value="30">30 seconds (Aggressive)</option>
                <option :value="60">60 seconds (Standard)</option>
                <option :value="300">5 minutes (Periodic)</option>
              </select>
            </div>

            <!-- Threshold Limits Config -->
            <div class="border-t border-white/5 pt-4 mt-2">
              <h4 class="text-xs font-bold text-violet-400 mb-3 flex items-center gap-1.5">
                <i class="fa-solid fa-triangle-exclamation"></i> Alert Threshold Warning Levels
              </h4>
              <div class="grid grid-cols-3 gap-3">
                <div>
                  <label for="edit_ssl_threshold" class="block text-[10px] font-semibold text-gray-300 mb-1">SSL Expiry (Days)</label>
                  <input type="number" id="edit_ssl_threshold" min="1" required v-model="editingWebsite.sslExpiryThreshold" class="w-full glass-input px-2.5 py-1.5 rounded-lg text-gray-200 text-xs">
                </div>
                <div>
                  <label for="edit_dns_threshold" class="block text-[10px] font-semibold text-gray-300 mb-1">DNS Lookup (ms)</label>
                  <input type="number" id="edit_dns_threshold" min="1" required v-model="editingWebsite.dnsLookupThreshold" class="w-full glass-input px-2.5 py-1.5 rounded-lg text-gray-200 text-xs">
                </div>
                <div>
                  <label for="edit_ewma_threshold" class="block text-[10px] font-semibold text-gray-300 mb-1">EWMA Latency (ms)</label>
                  <input type="number" id="edit_ewma_threshold" min="1" required v-model="editingWebsite.ewmaThreshold" class="w-full glass-input px-2.5 py-1.5 rounded-lg text-gray-200 text-xs">
                </div>
              </div>
            </div>
          </div>

          <footer class="flex items-center justify-end gap-3">
            <button type="button" @click="closeEditModal" class="px-4 py-2 border border-white/5 bg-white/5 hover:bg-white/10 text-gray-300 rounded-lg text-xs font-semibold transition duration-150">Cancel</button>
            <button type="submit" :disabled="formSubmitting" class="px-4 py-2 bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white rounded-lg text-xs font-semibold shadow-md shadow-violet-950/25 flex items-center gap-2 transition duration-150">
              <i class="fa-solid fa-arrows-spin animate-spin" v-if="formSubmitting"></i>
              Save Changes
            </button>
          </footer>
        </form>
      </div>
    </div>
    <!-- GLOBAL SETTINGS MODAL (Glassmorphic) -->
    <div v-if="settingsModalOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm transition-all duration-300">
      <div class="glass-card w-full max-w-md rounded-2xl p-6 shadow-2xl relative border border-white/10" @click.stop>
        <header class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-white flex items-center gap-2">
            <i class="fa-solid fa-sliders text-violet-400"></i> Global Threshold Limits
          </h3>
          <button @click="closeSettingsModal" class="text-gray-400 hover:text-white transition">
            <i class="fa-solid fa-xmark text-lg"></i>
          </button>
        </header>

        <form @submit.prevent="saveSettings">
          <div class="flex flex-col gap-4 text-sm mb-6">
            <div>
              <label for="global_connection_timeout" class="block text-xs font-semibold text-gray-300 mb-1.5">Connection Timeout (ms)</label>
              <input type="number" id="global_connection_timeout" required v-model="globalSettings.connectionTimeout" min="1" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>

            <div>
              <label for="global_retry_count" class="block text-xs font-semibold text-gray-300 mb-1.5">Retry Count</label>
              <input type="number" id="global_retry_count" required v-model="globalSettings.retryCount" min="0" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>

            <div>
              <label for="global_ssl_expiry" class="block text-xs font-semibold text-gray-300 mb-1.5">SSL Expiry Warning (Days)</label>
              <input type="number" id="global_ssl_expiry" required v-model="globalSettings.sslExpiryThreshold" min="1" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>

            <div>
              <label for="global_dns_lookup" class="block text-xs font-semibold text-gray-300 mb-1.5">DNS Lookup Warning (ms)</label>
              <input type="number" id="global_dns_lookup" required v-model="globalSettings.dnsLookupThreshold" step="0.1" min="0.1" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>

            <div>
              <label for="global_ewma" class="block text-xs font-semibold text-gray-300 mb-1.5">EWMA Latency Warning (ms)</label>
              <input type="number" id="global_ewma" required v-model="globalSettings.ewmaThreshold" step="0.1" min="0.1" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>
          </div>

          <footer class="flex items-center justify-end gap-3">
            <button type="button" @click="closeSettingsModal" class="px-4 py-2 border border-white/5 bg-white/5 hover:bg-white/10 text-gray-300 rounded-lg text-xs font-semibold transition duration-150">Cancel</button>
            <button type="submit" :disabled="settingsSubmitting" class="px-4 py-2 bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white rounded-lg text-xs font-semibold shadow-md shadow-violet-950/25 flex items-center gap-2 transition duration-150">
              <i class="fa-solid fa-arrows-spin animate-spin" v-if="settingsSubmitting"></i>
              Save Settings
            </button>
          </footer>
        </form>
      </div>
    </div>

    <!-- PORTAL CUSTOMIZATION MODAL (Glassmorphic) -->
    <div v-if="customizationModalOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm transition-all duration-300">
      <div class="glass-card w-full max-w-2xl rounded-2xl p-6 shadow-2xl relative border border-white/10" @click.stop>
        <header class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold text-white flex items-center gap-2">
            <i class="fa-solid fa-palette text-violet-400"></i> 
            {{ showCustomizationForm || isEditingCustomization ? (isEditingCustomization ? 'Edit Layout Configuration' : 'Create Layout Configuration') : 'Portal Layout Customizations' }}
          </h3>
          <button @click="closeCustomizationModal" class="text-gray-400 hover:text-white transition">
            <i class="fa-solid fa-xmark text-lg"></i>
          </button>
        </header>

        <!-- Form View -->
        <form v-if="showCustomizationForm || isEditingCustomization" @submit.prevent="handleCustomizationSubmit">
          <div class="grid grid-cols-1 md:grid-cols-2 gap-4 text-sm mb-6">
            <div>
              <label class="block text-xs font-semibold text-gray-300 mb-1.5">Layout Name (Unique)</label>
              <input type="text" required v-model="customizationForm.name" placeholder="e.g. Neon Purple" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>
            <div>
              <label class="block text-xs font-semibold text-gray-300 mb-1.5">Header Text (Title)</label>
              <input type="text" required v-model="customizationForm.headerText" placeholder="e.g. WSMS Obs" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>
            <div>
              <label class="block text-xs font-semibold text-gray-300 mb-1.5">Logo URL or Path</label>
              <input type="text" required v-model="customizationForm.logoUrl" placeholder="e.g. /src/assets/logo.svg" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>
            <div>
              <label class="block text-xs font-semibold text-gray-300 mb-1.5">Body Description (Login Page)</label>
              <input type="text" required v-model="customizationForm.bodyContent" placeholder="e.g. Advanced Observability Platform" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>
            <div class="md:col-span-2">
              <label class="block text-xs font-semibold text-gray-300 mb-1.5">Footer Text</label>
              <input type="text" required v-model="customizationForm.footerText" placeholder="e.g. Standalone Version 1.0.0" class="w-full glass-input px-3.5 py-2 rounded-lg text-gray-200">
            </div>
          </div>

          <footer class="flex items-center justify-end gap-3 border-t border-white/5 pt-4">
            <button type="button" @click="cancelCustomizationForm" class="px-4 py-2 border border-white/5 bg-white/5 hover:bg-white/10 text-gray-300 rounded-lg text-xs font-semibold transition duration-150">Back to List</button>
            <button type="submit" :disabled="customizationFormSubmitting" class="px-4 py-2 bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white rounded-lg text-xs font-semibold shadow-md shadow-violet-950/25 flex items-center gap-2 transition duration-150">
              <i class="fa-solid fa-arrows-spin animate-spin" v-if="customizationFormSubmitting"></i>
              {{ isEditingCustomization ? 'Save Changes' : 'Create Layout' }}
            </button>
          </footer>
        </form>

        <!-- List View -->
        <div v-else>
          <div class="flex justify-between items-center mb-4">
            <span class="text-xs text-gray-400">Select an active layout configuration or manage existing ones.</span>
            <button type="button" @click="showCustomizationForm = true" class="px-3 py-1.5 bg-violet-600/20 hover:bg-violet-600/30 text-violet-400 border border-violet-500/30 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition">
              <i class="fa-solid fa-plus"></i> Create New
            </button>
          </div>

          <div class="max-h-[350px] overflow-y-auto pr-1 flex flex-col gap-3">
            <div v-for="item in customizationsList" :key="item.id" class="p-4 rounded-xl border flex items-center justify-between transition-all" :class="item.isActive ? 'bg-violet-950/30 border-violet-500/50 shadow-md shadow-violet-950/20' : 'bg-white/5 border-white/5 hover:bg-white/10'">
              <div class="flex items-center gap-3 min-w-0">
                <div class="w-10 h-10 rounded-lg bg-indigo-950/40 border border-white/5 flex items-center justify-center overflow-hidden flex-shrink-0">
                  <img v-if="item.logoUrl && (item.logoUrl.startsWith('/') || item.logoUrl.startsWith('http'))" :src="item.logoUrl" class="w-full h-full object-contain" alt="Logo">
                  <i v-else class="fa-solid fa-tower-broadcast text-lg text-violet-400"></i>
                </div>
                <div class="min-w-0">
                  <div class="flex items-center gap-2">
                    <h4 class="font-bold text-sm text-white truncate">{{ item.name }}</h4>
                    <span v-if="item.isActive" class="text-[9px] bg-violet-600/30 text-violet-400 font-semibold px-2 py-0.5 rounded-full border border-violet-500/20 uppercase">Active</span>
                  </div>
                  <p class="text-xs text-gray-400 truncate mt-0.5">Header: {{ item.headerText }}</p>
                </div>
              </div>
              
              <div class="flex items-center gap-2">
                <button v-if="!item.isActive" @click="activateCustomization(item.id)" class="px-2.5 py-1 bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white rounded-lg text-xs font-semibold transition shadow-md shadow-indigo-900/20">
                  Activate
                </button>
                <button @click="editCustomization(item)" class="p-1.5 bg-white/5 hover:bg-white/10 border border-white/5 text-gray-300 hover:text-white rounded-lg transition" title="Edit">
                  <i class="fa-solid fa-pencil text-xs"></i>
                </button>
                <button v-if="!item.isActive" @click="deleteCustomization(item.id)" class="p-1.5 bg-white/5 hover:bg-rose-950/20 border border-white/5 text-gray-400 hover:text-rose-400 rounded-lg transition" title="Delete">
                  <i class="fa-solid fa-trash-can text-xs"></i>
                </button>
              </div>
            </div>
            <div v-if="customizationsList.length === 0" class="text-center py-8 text-gray-500 text-xs">
              No custom layouts created yet. Click "Create New" to get started.
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Main Dashboard Footer -->
    <footer class="mt-12 py-6 border-t border-white/5 text-center text-xs text-gray-500 relative z-10">
      <p>{{ activeCustomization.footerText }}</p>
    </footer>

    <!-- AI Monitoring Chat Assistant -->
    <ChatAssistant />
  </div>
</template>
