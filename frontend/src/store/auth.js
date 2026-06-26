import { ref, computed } from 'vue';

const accessToken = ref(localStorage.getItem('access_token') || null);
const refreshToken = ref(localStorage.getItem('refresh_token') || null);
const username = ref(localStorage.getItem('username') || null);
const email = ref(localStorage.getItem('email') || null);

// Safe parsing of roles to prevent fatal crashes if localStorage has legacy non-JSON formats
let initialRoles = [];
try {
  const storedRoles = localStorage.getItem('roles');
  if (storedRoles) {
    if (storedRoles.startsWith('[')) {
      initialRoles = JSON.parse(storedRoles);
    } else {
      initialRoles = [storedRoles];
    }
  }
} catch (e) {
  console.error("Failed to parse roles from localStorage:", e);
  initialRoles = [];
}
const roles = ref(initialRoles);

const isLoggedIn = computed(() => !!accessToken.value);
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'));
const isOperator = computed(() => roles.value.includes('ROLE_OPERATOR') || roles.value.includes('ROLE_ADMIN'));

function setTokens(authData) {
  accessToken.value = authData.accessToken;
  refreshToken.value = authData.refreshToken;
  username.value = authData.username;
  email.value = authData.email;
  roles.value = authData.roles;

  localStorage.setItem('access_token', authData.accessToken);
  localStorage.setItem('refresh_token', authData.refreshToken);
  localStorage.setItem('username', authData.username);
  localStorage.setItem('email', authData.email);
  localStorage.setItem('roles', JSON.stringify(authData.roles));
}

function clearTokens() {
  accessToken.value = null;
  refreshToken.value = null;
  username.value = null;
  email.value = null;
  roles.value = [];

  localStorage.removeItem('access_token');
  localStorage.removeItem('refresh_token');
  localStorage.removeItem('username');
  localStorage.removeItem('email');
  localStorage.removeItem('roles');
}

export const useAuthStore = () => ({
  accessToken,
  refreshToken,
  username,
  email,
  roles,
  isLoggedIn,
  isAdmin,
  isOperator,
  setTokens,
  clearTokens
});
