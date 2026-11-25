<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import RagComposer from '../components/RagComposer.vue'
import RagMessage from '../components/RagMessage.vue'
import { USE_MOCK } from '../api/config'
import { useRagChat } from '../composables/useRagChat'

const {
  messages,
  isStreaming,
  datasetId,
  traceId,
  firstTokenMs,
  totalMs,
  send,
  stop,
  reset,
} = useRagChat()

const mockMode = USE_MOCK
const draft = ref('')
const listRef = ref(null)

const examples = [
  '2027年元旦放假几天？',
  '双友好医院建设包括哪五个友好？',
  '事业单位登记管理机关是什么？',
]

async function handleSend(text) {
  const accepted = await send(text)
  if (accepted) {
    draft.value = ''
  }
}

function useExample(index) {
  draft.value = examples[index] || examples[0]
}

function setDataset(value) {
  datasetId.value = value === 'A' ? 'A' : 'B'
}

watch(
  messages,
  async () => {
    await nextTick()
    const list = listRef.value
    if (list) {
      list.scrollTop = list.scrollHeight
    }
  },
  { deep: true },
)

onBeforeUnmount(() => {
  stop()
})
</script>

<template>
  <section class="chat-view rag-view">
    <div class="chat-toolbar">
      <div>
        <h2>知识库问答</h2>
        <p class="subtitle">选择 A/B 知识库后提问；回答只依据检索到的资料，并在结束前附上引用。</p>
      </div>
      <button
        type="button"
        class="ghost-button"
        :disabled="isStreaming"
        @click="reset"
      >
        清空问答
      </button>
    </div>

    <div v-if="mockMode" class="rag-mock-notice" role="status">
      <strong>RAG 问答需要连接真实后端</strong>
      <span>当前为演示模式，页面不会生成伪造回答或引用。请设置 <code>VITE_USE_MOCK=false</code> 并启动后端后使用。</span>
    </div>

    <div
      ref="listRef"
      class="chat-list rag-list"
      aria-live="polite"
      :aria-busy="isStreaming"
    >
      <RagMessage v-for="message in messages" :key="message.id" :message="message" />
    </div>

    <div class="prompt-hints">
      <button
        v-for="(example, index) in examples"
        :key="example"
        type="button"
        class="prompt-chip"
        :disabled="mockMode"
        @click="useExample(index)"
      >
        {{ example }}
      </button>
    </div>

    <RagComposer
      v-model="draft"
      :dataset-id="datasetId"
      :is-streaming="isStreaming"
      :disabled="mockMode"
      @update:dataset-id="setDataset"
      @send="handleSend"
      @stop="stop"
      @example="useExample(0)"
    />

    <div class="chat-meta">
      <span v-if="mockMode">演示模式 · 未调用真实 RAG</span>
      <span v-if="firstTokenMs">首字 {{ (firstTokenMs / 1000).toFixed(2) }}s</span>
      <span v-if="totalMs">总耗时 {{ (totalMs / 1000).toFixed(2) }}s</span>
      <span v-if="traceId">traceId: {{ traceId }}</span>
      <span v-if="!mockMode && !firstTokenMs && !traceId">等待发送…</span>
      <span class="rag-meta-dataset">当前知识库 {{ datasetId }}</span>
    </div>
  </section>
</template>
