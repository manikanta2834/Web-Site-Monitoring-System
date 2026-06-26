<template>
  <div class="fixed bottom-6 right-6 z-50">
    <!-- Chat Icon Toggle Button -->
    <button 
      @click="toggleChat"
      class="bg-gradient-to-tr from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white p-4 rounded-full shadow-2xl shadow-violet-950/50 flex items-center justify-center transition-all duration-300 hover:scale-105 focus:outline-none"
    >
      <i class="fa-solid" :class="isOpen ? 'fa-xmark text-xl' : 'fa-robot text-2xl'"></i>
    </button>

    <!-- Chat Drawer -->
    <div 
      v-if="isOpen"
      class="absolute bottom-20 right-0 w-[420px] h-[550px] glass-card rounded-3xl border border-white/10 shadow-2xl overflow-hidden flex flex-col z-50 bg-[#090d19]/95"
    >
      <!-- Header -->
      <header class="p-4 bg-gradient-to-r from-violet-950/40 to-indigo-950/40 border-b border-white/10 flex justify-between items-center">
        <div class="flex items-center gap-2.5">
          <div class="bg-violet-600/30 p-2 rounded-xl text-violet-400">
            <i class="fa-solid fa-brain"></i>
          </div>
          <div>
            <h3 class="text-sm font-bold text-white tracking-tight">WSMS Telemetry AI</h3>
            <p class="text-[10px] text-emerald-400 flex items-center gap-1">
              <span class="inline-block w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
              Local Qwen-2.5 Active
            </p>
          </div>
        </div>
      </header>

      <!-- Message Area -->
      <div class="flex-1 overflow-y-auto p-4 flex flex-col gap-4" ref="messageBox">
        <div 
          v-for="(msg, i) in messages" 
          :key="i"
          :class="['max-w-[85%] rounded-2xl p-3.5 text-xs leading-relaxed', msg.role === 'user' ? 'self-end bg-violet-600/25 border border-violet-500/20 text-gray-200' : 'self-start bg-white/5 border border-white/5 text-gray-300']"
        >
          <!-- Message text with basic markdown rendering -->
          <div v-html="renderMarkdown(msg.text)" class="markdown-body"></div>
          
          <!-- Metadata (Generated SQL query) -->
          <details v-if="msg.sql && msg.sql.trim() !== ''" class="mt-2 text-[10px] text-indigo-400 cursor-pointer bg-black/30 p-2 rounded-lg border border-white/5">
            <summary class="font-semibold select-none outline-none">Generated Telemetry Query</summary>
            <pre class="mt-1 overflow-x-auto whitespace-pre-wrap font-mono bg-black/20 p-1.5 rounded">{{ msg.sql }}</pre>
          </details>
        </div>

        <!-- Loader -->
        <div v-if="loading" class="self-start bg-white/5 border border-white/5 rounded-2xl p-3.5 text-xs text-gray-400 flex items-center gap-2.5">
          <i class="fa-solid fa-circle-notch animate-spin text-violet-400"></i>
          <span>Querying local neural core...</span>
        </div>
      </div>

      <!-- Input Form -->
      <form @submit.prevent="sendMessage" class="p-3 border-t border-white/10 bg-white/5 flex gap-2">
        <input 
          type="text" 
          v-model="input" 
          placeholder="Ask about website status, outages, or SQL queries..." 
          class="flex-1 glass-input px-4 py-2 rounded-xl text-xs text-gray-200 placeholder-gray-500 focus:outline-none"
          :disabled="loading"
        />
        <button 
          type="submit" 
          class="bg-violet-600 hover:bg-violet-500 text-white px-4 py-2 rounded-xl text-xs font-semibold transition duration-200 disabled:opacity-50"
          :disabled="loading || !input.trim()"
        >
          <i class="fa-solid fa-paper-plane"></i>
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue';
import apiClient from '../api/client';

const isOpen = ref(false);
const loading = ref(false);
const input = ref('');
const messageBox = ref(null);

const messages = ref([
  { role: 'assistant', text: 'Welcome back. I am connected directly to your PostgreSQL monitoring ledger. Ask me to query outages, review SSL lifespans, or explain lag trends.' }
]);

const toggleChat = () => {
  isOpen.value = !isOpen.value;
  if (isOpen.value) {
    scrollToBottom();
  }
};

const scrollToBottom = async () => {
  await nextTick();
  if (messageBox.value) {
    messageBox.value.scrollTop = messageBox.value.scrollHeight;
  }
};

const sendMessage = async () => {
  if (!input.value.trim() || loading.value) return;

  const userText = input.value;
  messages.value.push({ role: 'user', text: userText });
  input.value = '';
  loading.value = true;
  scrollToBottom();

  try {
    const response = await apiClient.post('/api/ai/chat', { message: userText });
    messages.value.push({
      role: 'assistant',
      text: response.data.response,
      sql: response.data.generatedSql
    });
  } catch (err) {
    messages.value.push({
      role: 'assistant',
      text: 'Error accessing the neural gateway: ' + (err.response?.data?.message || err.message)
    });
  } finally {
    loading.value = false;
    scrollToBottom();
  }
};

const renderMarkdown = (text) => {
  if (!text) return '';
  
  // Escape HTML tags to prevent XSS
  let escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
    
  return escaped
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.*?)\*/g, '<em>$1</em>')
    .replace(/`(.*?)`/g, '<code class="bg-black/40 px-1 py-0.5 rounded font-mono text-violet-300 text-[11px]">$1</code>')
    .replace(/^- (.*)/gm, '• $1')
    .replace(/\n/g, '<br/>');
};
</script>

<style scoped>
.glass-card {
  background: rgba(9, 13, 25, 0.9);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}
.glass-input {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.glass-input:focus {
  border-color: rgba(139, 92, 246, 0.5);
  background: rgba(255, 255, 255, 0.05);
}
.markdown-body :deep(strong) {
  color: #fff;
  font-weight: 600;
}
.markdown-body :deep(code) {
  word-break: break-all;
}
</style>
