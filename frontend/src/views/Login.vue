<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import apiClient from '../api/client';
import { useAuthStore } from '../store/auth';
import * as THREE from 'three';

const showTurnstile = ref(true);
const turnstileToken = ref('');

const RECAPTCHA_SITE_KEY = '6LeIxAcTAAAAAJcZVRqyHh71UMTErE_Yth5aqaF1';
const TURNSTILE_SITE_KEY = '1x00000000000000000000AA';

const router = useRouter();
const authStore = useAuthStore();

// Form state
const usernameOrEmail = ref('');
const email = ref('');
const password = ref('');
const confirmPassword = ref('');
const loading = ref(false);
const errorMessage = ref('');
const successMessage = ref('');
const isShaking = ref(false);
const isRegisterMode = ref(false);

const publicKeyPEM = ref('');

const importPublicKey = async (pemBase64) => {
  const binaryDerString = window.atob(pemBase64);
  const binaryDer = new ArrayBuffer(binaryDerString.length);
  const binaryDerView = new Uint8Array(binaryDer);
  for (let i = 0; i < binaryDerString.length; i++) {
    binaryDerView[i] = binaryDerString.charCodeAt(i);
  }
  return await window.crypto.subtle.importKey(
    "spki",
    binaryDer,
    {
      name: "RSA-OAEP",
      hash: "SHA-256"
    },
    true,
    ["encrypt"]
  );
};

const encryptPassword = async (plainText) => {
  if (!publicKeyPEM.value) {
    throw new Error('Encryption key not loaded. Please try again.');
  }
  const importedKey = await importPublicKey(publicKeyPEM.value);
  const encoder = new TextEncoder();
  const data = encoder.encode(plainText);
  const encryptedBuffer = await window.crypto.subtle.encrypt(
    {
      name: "RSA-OAEP"
    },
    importedKey,
    data
  );
  const encryptedBytes = new Uint8Array(encryptedBuffer);
  let binary = '';
  const len = encryptedBytes.byteLength;
  for (let i = 0; i < len; i++) {
    binary += String.fromCharCode(encryptedBytes[i]);
  }
  return window.btoa(binary);
};

const fetchPublicKey = async () => {
  try {
    const response = await apiClient.get('/api/auth/public-key');
    publicKeyPEM.value = response.data.publicKey;
  } catch (err) {
    console.error('Failed to fetch public key for E2E password encryption:', err);
    errorMessage.value = 'Failed to establish secure connection parameters. Please refresh the page.';
  }
};

// Three.js container ref
const canvasContainer = ref(null);
let scene, camera, renderer, particleSystem, linesSystem, starsSystem;
let animationFrameId;

// Voice alert helper
const speakAlert = (text) => {
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  
  const voices = window.speechSynthesis.getVoices();
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
           name.includes(' Susan');
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

  if (femaleVoice) utterance.voice = femaleVoice;
  utterance.rate = 0.92;
  utterance.pitch = 1.15;
  window.speechSynthesis.speak(utterance);
};

// Handle submission
const handleSubmit = async () => {
  if (loading.value) return;
  errorMessage.value = '';
  successMessage.value = '';
  isShaking.value = false;

  if (isRegisterMode.value) {
    if (password.value !== confirmPassword.value) {
      errorMessage.value = 'Passwords do not match.';
      isShaking.value = true;
      speakAlert('Passwords do not match.');
      setTimeout(() => { isShaking.value = false; }, 600);
      return;
    }
    
    loading.value = true;
    try {
      if (!publicKeyPEM.value) {
        await fetchPublicKey();
      }
      const encryptedPassword = await encryptPassword(password.value);
      await apiClient.post('/api/auth/register', {
        username: usernameOrEmail.value,
        email: email.value,
        password: encryptedPassword
      });

      speakAlert('Account created successfully. You can now log in.');
      successMessage.value = 'Registration successful! Please log in below.';
      isRegisterMode.value = false;
      password.value = '';
      confirmPassword.value = '';
    } catch (error) {
      errorMessage.value = error.response?.data?.message || 'Registration failed. Please check your inputs.';
      isShaking.value = true;
      speakAlert('Registration failed.');
      setTimeout(() => { isShaking.value = false; }, 600);
    } finally {
      loading.value = false;
    }
  } else {
    // Verify that the Turnstile captcha token is present
    if (!turnstileToken.value) {
      errorMessage.value = 'Please complete the CAPTCHA verification check.';
      isShaking.value = true;
      speakAlert('Please complete the verification.');
      setTimeout(() => { isShaking.value = false; }, 600);
      return;
    }

    loading.value = true;
    try {
      if (!publicKeyPEM.value) {
        await fetchPublicKey();
      }
      const encryptedPassword = await encryptPassword(password.value);
      const response = await apiClient.post('/api/auth/login', {
        usernameOrEmail: usernameOrEmail.value,
        password: encryptedPassword,
        captchaToken: turnstileToken.value,
        captchaProvider: 'turnstile'
      });

      authStore.setTokens(response.data);
      
      const role = response.data.roles && response.data.roles.length > 0 ? response.data.roles[0].replace('ROLE_', '').toLowerCase() : 'user';
      speakAlert(`Access granted. Welcome back, ${response.data.username}. Initiating monitoring session as ${role}.`);
      
      router.push('/');
    } catch (error) {
      errorMessage.value = error.response?.data?.message || 'Authentication failed. Please verify credentials.';
      isShaking.value = true;
      
      if (window.turnstile) {
        window.turnstile.reset();
        turnstileToken.value = '';
      }

      if (error.response?.status === 423 || error.response?.status === 401 && errorMessage.value.includes('locked')) {
        speakAlert("Access denied. Account is temporarily locked due to excessive failed attempts.");
      } else {
        speakAlert("Access denied. Invalid credentials.");
      }

      setTimeout(() => {
        isShaking.value = false;
      }, 600);
    } finally {
      loading.value = false;
    }
  }
};

const handleGoogleStub = () => {
  speakAlert("Google integration is configured in client workflow stub mode. Please use standard credentials.");
  errorMessage.value = 'Google login is stubbed. Please use standard credentials.';
  isShaking.value = true;
  setTimeout(() => { isShaking.value = false; }, 600);
};

const handleGithubStub = () => {
  speakAlert("GitHub authentication is configured in client workflow sandbox mode. Please use standard credentials.");
  errorMessage.value = 'GitHub login is stubbed. Please use standard credentials.';
  isShaking.value = true;
  setTimeout(() => { isShaking.value = false; }, 600);
};

let mouseX = 0, mouseY = 0;

// Initialize Three.js 3D Visualizer
const initThree = () => {
  if (!canvasContainer.value) return;

  let width = canvasContainer.value.clientWidth;
  let height = canvasContainer.value.clientHeight;
  if (width === 0 || height === 0) {
    const rect = canvasContainer.value.getBoundingClientRect();
    width = rect.width || 600;
    height = rect.height || 600;
  }

  // Scene
  scene = new THREE.Scene();
  scene.fog = new THREE.FogExp2(0x060913, 0.008);

  // Camera
  camera = new THREE.PerspectiveCamera(55, width / height, 0.1, 1000);
  camera.position.z = 85;

  // Renderer
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setSize(width, height);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  
  renderer.domElement.style.width = '100%';
  renderer.domElement.style.height = '100%';
  renderer.domElement.style.position = 'absolute';
  renderer.domElement.style.top = '0';
  renderer.domElement.style.left = '0';
  
  canvasContainer.value.appendChild(renderer.domElement);

  // 1. Digital Holographic Globe Core
  const globeGroup = new THREE.Group();
  
  // Wireframe globe mesh
  const sphereGeo = new THREE.SphereGeometry(18, 22, 22);
  const wireframeMat = new THREE.MeshBasicMaterial({
    color: 0x4f46e5,
    wireframe: true,
    transparent: true,
    opacity: 0.14,
    blending: THREE.AdditiveBlending
  });
  const wireframeGlobe = new THREE.Mesh(sphereGeo, wireframeMat);
  globeGroup.add(wireframeGlobe);

  // Globe point cloud (dense surface nodes representing monitoring points)
  const globePointsGeo = new THREE.SphereGeometry(18.1, 36, 36);
  const globePointsMat = new THREE.PointsMaterial({
    size: 0.6,
    color: 0x06b6d4, // Neon Cyan
    transparent: true,
    opacity: 0.5,
    blending: THREE.AdditiveBlending
  });
  const globePointCloud = new THREE.Points(globePointsGeo, globePointsMat);
  globeGroup.add(globePointCloud);

  scene.add(globeGroup);

  // 2. Cybernetic Monitoring Orbiting Rings & Data Packets
  const ringsGroup = new THREE.Group();
  const ringsCount = 3;
  const ringsData = [];

  const createCircleTexture = (colorStr, size = 16) => {
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    const grad = ctx.createRadialGradient(size/2, size/2, 0, size/2, size/2, size/2);
    grad.addColorStop(0, colorStr);
    grad.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, size, size);
    return new THREE.CanvasTexture(canvas);
  };

  const ringColors = [0x8b5cf6, 0x10b981, 0x3b82f6]; // Violet, Emerald, Blue

  for (let r = 0; r < ringsCount; r++) {
    const ringRadius = 23 + r * 3;
    const ringPointsCount = 60;
    const ringGeometry = new THREE.BufferGeometry();
    const ringPositions = new Float32Array(ringPointsCount * 3);

    for (let i = 0; i < ringPointsCount; i++) {
      const angle = (i / ringPointsCount) * Math.PI * 2;
      ringPositions[i * 3] = Math.cos(angle) * ringRadius;
      ringPositions[i * 3 + 1] = Math.sin(angle) * ringRadius;
      ringPositions[i * 3 + 2] = 0;
    }

    ringGeometry.setAttribute('position', new THREE.BufferAttribute(ringPositions, 3));

    const ringMaterial = new THREE.PointsMaterial({
      size: 2.2,
      color: ringColors[r],
      map: createCircleTexture('rgba(255,255,255,1)'),
      transparent: true,
      opacity: 0.85,
      blending: THREE.AdditiveBlending,
      depthWrite: false
    });

    const ringPoints = new THREE.Points(ringGeometry, ringMaterial);
    
    // Inclines rings randomly
    ringPoints.rotation.x = Math.random() * Math.PI;
    ringPoints.rotation.y = Math.random() * Math.PI;
    
    ringsGroup.add(ringPoints);
    ringsData.push({
      mesh: ringPoints,
      speedX: 0.0025 + r * 0.0015,
      speedY: 0.004 - r * 0.0015,
      speedZ: 0.006 + r * 0.001
    });
  }
  scene.add(ringsGroup);

  // 3. Constellation Nodes & Connecting Telemetry Lines
  const particlesCount = 100;
  const positions = new Float32Array(particlesCount * 3);
  const colors = new Float32Array(particlesCount * 3);

  const colorPalette = [
    new THREE.Color(0x8b5cf6), // Violet
    new THREE.Color(0x6366f1), // Indigo
    new THREE.Color(0x10b981)  // Emerald
  ];

  for (let i = 0; i < particlesCount; i++) {
    // Distribute nodes in a sphere shell around the globe
    const u = Math.random();
    const v = Math.random();
    const theta = u * 2.0 * Math.PI;
    const phi = Math.acos(2.0 * v - 1.0);
    const r = 32 + Math.random() * 12;

    positions[i * 3] = r * Math.sin(phi) * Math.cos(theta);
    positions[i * 3 + 1] = r * Math.sin(phi) * Math.sin(theta);
    positions[i * 3 + 2] = r * Math.cos(phi);

    const color = colorPalette[Math.floor(Math.random() * colorPalette.length)];
    colors[i * 3] = color.r;
    colors[i * 3 + 1] = color.g;
    colors[i * 3 + 2] = color.b;
  }

  const particleGeometry = new THREE.BufferGeometry();
  particleGeometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  particleGeometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));

  const particleMaterial = new THREE.PointsMaterial({
    size: 2.8,
    map: createCircleTexture('rgba(255,255,255,1)'),
    vertexColors: true,
    transparent: true,
    opacity: 0.8,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  });

  particleSystem = new THREE.Points(particleGeometry, particleMaterial);
  scene.add(particleSystem);

  // 4. Interconnecting Lines
  const linesGeometry = new THREE.BufferGeometry();
  const linePositions = [];
  const maxConnections = 80;

  for (let i = 0; i < particlesCount; i++) {
    for (let j = i + 1; j < particlesCount; j++) {
      const dx = positions[i * 3] - positions[j * 3];
      const dy = positions[i * 3 + 1] - positions[j * 3 + 1];
      const dz = positions[i * 3 + 2] - positions[j * 3 + 2];
      const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

      if (dist < 15 && linePositions.length / 6 < maxConnections) {
        linePositions.push(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
        linePositions.push(positions[j * 3], positions[j * 3 + 1], positions[j * 3 + 2]);
      }
    }
  }

  linesGeometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(linePositions), 3));
  const linesMaterial = new THREE.LineBasicMaterial({
    color: 0x6366f1,
    transparent: true,
    opacity: 0.12,
    blending: THREE.AdditiveBlending
  });

  linesSystem = new THREE.LineSegments(linesGeometry, linesMaterial);
  scene.add(linesSystem);

  // 5. Background Stars
  const starsCount = 300;
  const starsPositions = new Float32Array(starsCount * 3);
  for (let i = 0; i < starsCount; i++) {
    starsPositions[i * 3] = (Math.random() - 0.5) * 400;
    starsPositions[i * 3 + 1] = (Math.random() - 0.5) * 400;
    starsPositions[i * 3 + 2] = (Math.random() - 0.5) * 400;
  }
  const starsGeometry = new THREE.BufferGeometry();
  starsGeometry.setAttribute('position', new THREE.BufferAttribute(starsPositions, 3));
  const starsMaterial = new THREE.PointsMaterial({
    size: 0.8,
    color: 0xffffff,
    transparent: true,
    opacity: 0.25
  });
  starsSystem = new THREE.Points(starsGeometry, starsMaterial);
  scene.add(starsSystem);

  // Animation loop
  let clock = new THREE.Clock();
  const animate = () => {
    animationFrameId = requestAnimationFrame(animate);

    const time = clock.getElapsedTime();

    // Rotate holographic core globe
    globeGroup.rotation.y = time * 0.05;
    globeGroup.rotation.x = time * 0.015;

    // Orbiting data rings
    ringsData.forEach(ring => {
      ring.mesh.rotation.x += ring.speedX;
      ring.mesh.rotation.y += ring.speedY;
      ring.mesh.rotation.z += ring.speedZ;
    });

    if (particleSystem) {
      particleSystem.rotation.y = time * 0.02;
    }
    if (linesSystem) {
      linesSystem.rotation.y = time * 0.02;
    }
    if (starsSystem) {
      starsSystem.rotation.y = time * 0.005;
    }

    // Dynamic breathing/pulsing
    const pulseFactor = Math.sin(time * 2.0) * 0.2 + 0.8; // oscillates 0.6 to 1.0
    particleMaterial.size = 2.8 * pulseFactor;
    linesMaterial.opacity = 0.12 * (Math.sin(time * 1.5) * 0.3 + 0.7);

    // Mouse Parallax smooth transition (Interpolation)
    const targetX = mouseX * 22;
    const targetY = mouseY * 22;

    camera.position.x += (targetX - camera.position.x) * 0.04;
    camera.position.y += (targetY - camera.position.y) * 0.04;
    camera.lookAt(scene.position);

    renderer.render(scene, camera);
  };

  animate();
};

const handleResize = () => {
  if (!canvasContainer.value || !camera || !renderer) return;
  const width = canvasContainer.value.clientWidth;
  const height = canvasContainer.value.clientHeight;

  camera.aspect = width / height;
  camera.updateProjectionMatrix();
  renderer.setSize(width, height);
};

const onMouseMove = (event) => {
  if (!canvasContainer.value) return;
  const rect = canvasContainer.value.getBoundingClientRect();
  mouseX = ((event.clientX - rect.left) / rect.width) * 2 - 1;
  mouseY = -((event.clientY - rect.top) / rect.height) * 2 + 1;
};

const loadScript = (src, id) => {
  return new Promise((resolve, reject) => {
    if (document.getElementById(id)) {
      resolve();
      return;
    }
    const script = document.createElement('script');
    script.src = src;
    script.id = id;
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Failed to load script ${src}`));
    document.head.appendChild(script);
  });
};

const turnstileWidgetId = ref(null);

const renderTurnstile = () => {
  if (window.turnstile && !turnstileWidgetId.value) {
    const container = document.getElementById('turnstile-container');
    if (container) {
      try {
        turnstileWidgetId.value = window.turnstile.render('#turnstile-container', {
          sitekey: TURNSTILE_SITE_KEY,
          theme: 'dark',
          callback: (token) => {
            turnstileToken.value = token;
            errorMessage.value = '';
          },
          'expired-callback': () => {
            turnstileToken.value = '';
          },
          'error-callback': () => {
            turnstileToken.value = '';
          }
        });
      } catch (err) {
        console.error('Error rendering Turnstile widget:', err);
      }
    }
  }
};

watch(isRegisterMode, async (newVal) => {
  if (!newVal) {
    await nextTick();
    renderTurnstile();
  }
});

watch(showTurnstile, async (newVal) => {
  if (newVal) {
    await nextTick();
    renderTurnstile();
  }
});

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
    console.error('Failed to load active portal customization:', err);
  }
};

onMounted(async () => {
  fetchActiveCustomization();
  initThree();
  fetchPublicKey();
  window.addEventListener('resize', handleResize);
  window.addEventListener('mousemove', onMouseMove);

  // Define global callback for Turnstile
  window.onloadTurnstileCallback = () => {
    renderTurnstile();
  };

  // 1. Load Google reCAPTCHA v3 script independently so failure does not block Turnstile
  loadScript(`https://www.google.com/recaptcha/api.js?render=${RECAPTCHA_SITE_KEY}`, 'recaptcha-script')
    .catch(err => {
      console.warn('Google reCAPTCHA v3 script failed to load. Operating in Turnstile-only mode.', err);
    });

  // 2. Load Cloudflare Turnstile script independently
  if (window.turnstile) {
    renderTurnstile();
  } else {
    loadScript('https://challenges.cloudflare.com/turnstile/v0/api.js?onload=onloadTurnstileCallback&render=explicit', 'turnstile-script')
      .catch(err => {
        console.error('Failed to load Cloudflare Turnstile script:', err);
        errorMessage.value = 'Security verification failed to load. Please check your internet connection or disable adblockers.';
      });
  }
});

onUnmounted(() => {
  window.removeEventListener('resize', handleResize);
  window.removeEventListener('mousemove', onMouseMove);
  cancelAnimationFrame(animationFrameId);
  if (renderer) {
    renderer.dispose();
  }
  
  // Clean up script tags to prevent memory leaks
  const recaptchaScript = document.getElementById('recaptcha-script');
  if (recaptchaScript) recaptchaScript.remove();
  const turnstileScript = document.getElementById('turnstile-script');
  if (turnstileScript) turnstileScript.remove();
  
  // Clean up any remaining recaptcha badge
  const badge = document.querySelector('.grecaptcha-badge');
  if (badge) badge.remove();
});
</script>

<template>
  <div class="min-h-screen w-full flex bg-[#060913] text-white relative overflow-hidden font-sans">
    
    <!-- 3D Visualization Pane (Left Side) -->
    <div ref="canvasContainer" class="hidden lg:block lg:w-[58%] h-screen relative bg-[#04060c]">
      <!-- Overlay Info Details -->
      <div class="absolute top-10 left-10 z-10 flex items-center gap-3">
        <div class="bg-gradient-to-tr from-violet-600 to-indigo-600 p-2.5 rounded-xl shadow-lg shadow-violet-900/30 flex items-center justify-center w-12 h-12 overflow-hidden">
          <img v-if="activeCustomization.logoUrl && (activeCustomization.logoUrl.startsWith('/') || activeCustomization.logoUrl.startsWith('http'))" 
               :src="activeCustomization.logoUrl" 
               class="w-full h-full object-contain" 
               alt="Logo" />
          <i v-else class="fa-solid fa-tower-broadcast text-xl text-white"></i>
        </div>
        <div>
          <h2 class="text-xl font-bold tracking-tight text-white uppercase">{{ activeCustomization.headerText }}</h2>
          <p class="text-xs text-gray-400">Observability Gateway & Observational Control</p>
        </div>
      </div>

      <div class="absolute bottom-10 left-10 z-10 max-w-md">
        <h3 class="text-lg font-bold text-violet-400 mb-1 flex items-center gap-2">
          <span class="inline-block w-2.5 h-2.5 rounded-full bg-emerald-400 pulse-green"></span>
          Enterprise Observatory Active
        </h3>
        <p class="text-xs text-gray-400 leading-relaxed">
          {{ activeCustomization.bodyContent }}
        </p>
      </div>
    </div>

    <!-- Obsidian Glassmorphism Auth Card (Right Side) -->
    <div class="w-full lg:w-[42%] flex items-center justify-center p-6 md:p-12 bg-gradient-to-b from-[#090d19] to-[#05070e] border-l border-white/5 relative z-10">
      
      <!-- Decorative Orbs -->
      <div class="absolute top-[20%] right-[-10%] w-[300px] h-[300px] glow-orb-1 rounded-full pointer-events-none opacity-40"></div>
      <div class="absolute bottom-[20%] left-[-10%] w-[300px] h-[300px] glow-orb-2 rounded-full pointer-events-none opacity-40"></div>

      <!-- Obsidian Card -->
      <div :class="[
        'w-full max-w-md glass-card rounded-3xl p-8 border border-white/10 shadow-2xl relative z-10 transition-transform duration-300',
        isShaking ? 'shake-animation border-rose-500/50' : ''
      ]">
        
        <!-- Header -->
        <header class="text-center mb-8">
          <h1 class="text-2xl font-bold text-white tracking-tight flex items-center justify-center gap-2">
            {{ isRegisterMode ? 'Create Account in' : 'Sign In to' }} <span class="bg-gradient-to-r from-violet-400 to-indigo-400 bg-clip-text text-transparent">{{ activeCustomization.headerText }}</span>
          </h1>
          <p class="text-xs text-gray-400 mt-2">
            {{ isRegisterMode ? 'Register a new telemetry operator account' : 'Enter credentials to authenticate into the telemetry suite' }}
          </p>
        </header>

        <!-- Notification Banners -->
        <div v-if="errorMessage" class="mb-5 p-3 rounded-xl bg-rose-950/40 border border-rose-500/30 text-rose-300 text-xs flex items-center gap-2.5">
          <i class="fa-solid fa-triangle-exclamation text-rose-400 text-sm"></i>
          <span>{{ errorMessage }}</span>
        </div>
        
        <div v-if="successMessage" class="mb-5 p-3 rounded-xl bg-emerald-950/40 border border-emerald-500/30 text-emerald-300 text-xs flex items-center gap-2.5">
          <i class="fa-solid fa-circle-check text-emerald-400 text-sm"></i>
          <span>{{ successMessage }}</span>
        </div>

        <!-- Credentials Form -->
        <form @submit.prevent="handleSubmit" class="flex flex-col gap-4">
          <div>
            <label for="username" class="block text-xs font-semibold text-gray-400 mb-1.5 uppercase tracking-wider">
              {{ isRegisterMode ? 'Username' : 'Username or Email' }}
            </label>
            <div class="relative">
              <span class="absolute inset-y-0 left-0 flex items-center pl-3.5 text-gray-500">
                <i class="fa-solid fa-user text-xs"></i>
              </span>
              <input 
                type="text" 
                id="username" 
                required 
                v-model="usernameOrEmail" 
                :placeholder="isRegisterMode ? 'telemetry_operator' : 'admin'" 
                class="w-full glass-input pl-10 pr-4 py-2.5 rounded-xl text-gray-200 text-sm focus:outline-none"
              >
            </div>
          </div>

          <!-- Email Field (Register Only) -->
          <div v-if="isRegisterMode">
            <label for="email" class="block text-xs font-semibold text-gray-400 mb-1.5 uppercase tracking-wider">Email Address</label>
            <div class="relative">
              <span class="absolute inset-y-0 left-0 flex items-center pl-3.5 text-gray-500">
                <i class="fa-solid fa-envelope text-xs"></i>
              </span>
              <input 
                type="email" 
                id="email" 
                required 
                v-model="email" 
                placeholder="operator@wsms.local" 
                class="w-full glass-input pl-10 pr-4 py-2.5 rounded-xl text-gray-200 text-sm focus:outline-none"
              >
            </div>
          </div>

          <div>
            <label for="password" class="block text-xs font-semibold text-gray-400 mb-1.5 uppercase tracking-wider">Password</label>
            <div class="relative">
              <span class="absolute inset-y-0 left-0 flex items-center pl-3.5 text-gray-500">
                <i class="fa-solid fa-lock text-xs"></i>
              </span>
              <input 
                type="password" 
                id="password" 
                required 
                v-model="password" 
                placeholder="••••••••••••" 
                class="w-full glass-input pl-10 pr-4 py-2.5 rounded-xl text-gray-200 text-sm focus:outline-none"
              >
            </div>
          </div>

          <!-- Confirm Password (Register Only) -->
          <div v-if="isRegisterMode">
            <label for="confirmPassword" class="block text-xs font-semibold text-gray-400 mb-1.5 uppercase tracking-wider">Confirm Password</label>
            <div class="relative">
              <span class="absolute inset-y-0 left-0 flex items-center pl-3.5 text-gray-500">
                <i class="fa-solid fa-lock text-xs"></i>
              </span>
              <input 
                type="password" 
                id="confirmPassword" 
                required 
                v-model="confirmPassword" 
                placeholder="••••••••••••" 
                class="w-full glass-input pl-10 pr-4 py-2.5 rounded-xl text-gray-200 text-sm focus:outline-none"
              >
            </div>
          </div>

          <!-- Cloudflare Turnstile Container -->
          <div v-show="showTurnstile && !isRegisterMode" class="my-3 flex flex-col items-center justify-center min-h-[65px] transition-all duration-300">
            <div v-if="!turnstileToken && !turnstileWidgetId" class="text-xs text-gray-500 flex items-center gap-2 py-3">
              <i class="fa-solid fa-circle-notch animate-spin text-violet-400 text-sm"></i>
              <span>Securing connection...</span>
            </div>
            <div id="turnstile-container"></div>
          </div>

          <!-- Submit Button -->
          <button 
            type="submit" 
            :disabled="loading" 
            class="w-full mt-4 bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white py-2.5 rounded-xl font-semibold text-sm shadow-lg shadow-violet-950/25 flex items-center justify-center gap-2.5 transition duration-200"
          >
            <i class="fa-solid fa-arrows-spin animate-spin" v-if="loading"></i>
            <i class="fa-solid fa-shield-halved" v-else></i>
            {{ isRegisterMode ? 'Register Operator Account' : 'Authorize Session' }}
          </button>
        </form>

        <!-- Toggle Mode Link -->
        <p class="text-xs text-gray-400 text-center mt-5">
          {{ isRegisterMode ? 'Already have an account?' : "Don't have an account?" }}
          <button 
            @click="isRegisterMode = !isRegisterMode; errorMessage = ''; successMessage = '';"
            class="text-violet-400 hover:text-violet-300 font-semibold ml-1 focus:outline-none"
          >
            {{ isRegisterMode ? 'Sign In' : 'Register Now' }}
          </button>
        </p>

        <!-- Divider -->
        <div class="flex items-center my-6">
          <div class="flex-1 border-t border-white/5"></div>
          <span class="px-3 text-[10px] text-gray-500 font-bold uppercase tracking-wider">or sign in with</span>
          <div class="flex-1 border-t border-white/5"></div>
        </div>

        <!-- Social logins (Google & GitHub) -->
        <div class="grid grid-cols-2 gap-3">
          <button 
            @click="handleGoogleStub" 
            class="bg-white/5 hover:bg-white/10 text-gray-200 border border-white/5 py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-2 transition duration-200"
          >
            <img src="https://www.gstatic.com/images/branding/product/1x/gsa_64dp.png" alt="Google" class="w-4 h-4">
            Google
          </button>
          
          <button 
            @click="handleGithubStub" 
            class="bg-white/5 hover:bg-white/10 text-gray-200 border border-white/5 py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-2 transition duration-200"
          >
            <i class="fa-brands fa-github text-sm text-white"></i>
            GitHub
          </button>
        </div>

        <!-- Footer -->
        <footer class="mt-8 text-center text-[10px] text-gray-500 leading-relaxed">
          <div class="font-medium text-gray-400 mb-1">{{ activeCustomization.footerText }}</div>
          <div>By signing in, you agree to comply with standard security and data handling policies.</div>
        </footer>

      </div>
    </div>
  </div>
</template>

<style scoped>
.glass-input {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.05);
  transition: all 0.2s ease;
}
.glass-input:focus {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(139, 92, 246, 0.5);
  box-shadow: 0 0 10px rgba(139, 92, 246, 0.15);
}

.shake-animation {
  animation: shake 0.5s ease-in-out;
}

@keyframes shake {
  0%, 100% { transform: translateX(0); }
  20%, 60% { transform: translateX(-6px); }
  40%, 80% { transform: translateX(6px); }
}

.glow-orb-1 {
  background: radial-gradient(circle, rgba(139, 92, 246, 0.15) 0%, rgba(0,0,0,0) 70%);
  filter: blur(40px);
}
.glow-orb-2 {
  background: radial-gradient(circle, rgba(79, 70, 229, 0.15) 0%, rgba(0,0,0,0) 70%);
  filter: blur(40px);
}

.pulse-green {
  box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.5);
  animation: pulse-green-anim 2s infinite;
}
@keyframes pulse-green-anim {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.5); }
  70% { transform: scale(1); box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
}
</style>
