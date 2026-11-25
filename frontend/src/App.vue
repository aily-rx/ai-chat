<script setup>
import { onMounted, ref } from 'vue'
import ChatHeader from './components/ChatHeader.vue'
import ChatView from './views/ChatView.vue'
import ExtractView from './views/ExtractView.vue'
import KnowledgeView from './views/KnowledgeView.vue'
import RagView from './views/RagView.vue'
import { checkHealth } from './api/health'
import { USE_MOCK } from './api/config'

const activeTab = ref('chat')
const status = ref(USE_MOCK ? 'mock' : 'checking')

async function refreshHealth() {
  if (USE_MOCK) {
    status.value = 'mock'
    return
  }

  status.value = 'checking'

  try {
    await checkHealth()
    status.value = 'online'
  } catch {
    status.value = 'offline'
  }
}

onMounted(refreshHealth)
</script>

<template>
  <div class="app-shell">
    <ChatHeader
      v-model:active-tab="activeTab"
      :status="status"
      @refresh="refreshHealth"
    />

    <main class="app-main">
      <ChatView v-show="activeTab === 'chat'" />
      <KnowledgeView v-if="activeTab === 'kb'" />
      <RagView v-show="activeTab === 'rag'" />
      <ExtractView v-show="activeTab === 'extract'" />
    </main>
  </div>
</template>
