<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  message: {
    type: Object,
    required: true,
  },
})

const highlightedIndex = ref(-1)
let highlightTimer = null

const isUser = computed(() => props.message.role === 'user')
const rendered = computed(() => renderMarkdown(props.message.content))
const hasBody = computed(() => Boolean(props.message.content) || props.message.status === 'streaming')
const showCitations = computed(() => props.message.citationsReceived && props.message.citations.length > 0)

const statusText = computed(() => {
  if (props.message.status === 'streaming') return '生成中…'
  if (props.message.status === 'stopped') return '已停止'
  if (props.message.status === 'error') return '生成失败'
  if (props.message.status === 'done' && props.message.refusal) return '资料不足'
  return ''
})

function hasPage(citation) {
  return citation.page !== null && citation.page !== undefined && citation.page !== ''
}

function formatScore(score) {
  return typeof score === 'number' && Number.isFinite(score) ? score.toFixed(2) : '—'
}

function citationAnchorId(index) {
  return `citation-${props.message.id}-${index}`
}

async function focusCitation(index) {
  await nextTick()
  const card = document.getElementById(citationAnchorId(index))
  if (!card) {
    return
  }

  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  card.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'center' })

  highlightedIndex.value = index
  if (highlightTimer) {
    window.clearTimeout(highlightTimer)
  }
  highlightTimer = window.setTimeout(() => {
    highlightedIndex.value = -1
    highlightTimer = null
  }, 2000)
}

onBeforeUnmount(() => {
  if (highlightTimer) {
    window.clearTimeout(highlightTimer)
  }
})
</script>

<template>
  <article :class="['message', `message--${message.role}`, `message--${message.status}`]">
    <div class="message__meta">
      <span>{{ isUser ? '你' : '助手' }}</span>
      <span v-if="statusText" class="message__status">{{ statusText }}</span>
    </div>

    <div v-if="hasBody" class="message__bubble" :class="{ 'message__markdown': !isUser }">
      <template v-if="isUser">{{ message.content }}</template>
      <div v-else class="message__content" v-html="rendered"></div>
      <span
        v-if="message.status === 'streaming'"
        class="stream-cursor"
        aria-hidden="true"
      ></span>
    </div>

    <div v-if="message.refusal" class="rag-refusal" role="status">
      <strong>资料中未找到依据</strong>
      <span>当前知识库没有检索到可支撑回答的资料，本次未生成引用。可尝试换一种问法，或切换知识库后重试。</span>
    </div>

    <div v-if="message.error" class="message__error" role="alert">
      <strong>错误码 {{ message.error.code }}</strong>
      <span>{{ message.error.message }}</span>
      <span v-if="message.error.traceId">traceId: {{ message.error.traceId }}</span>
    </div>

    <section v-if="showCitations" class="citation-section" aria-label="引用依据">
      <div class="citation-section__header">
        <strong>引用依据</strong>
        <span>{{ message.citations.length }} 条</span>
        <span class="citation-section__hint">点击编号定位到引用卡片</span>
      </div>

      <div class="citation-chips">
        <button
          v-for="(citation, index) in message.citations"
          :key="`chip-${citation.docId}-${citation.segmentIndex}`"
          type="button"
          class="citation-chip"
          :aria-label="`定位到引用 ${index + 1}：${citation.docName}`"
          :title="citation.docName"
          @click="focusCitation(index)"
        >
          [{{ index + 1 }}] {{ citation.docName }}
        </button>
      </div>

      <ol class="citation-list">
        <li
          v-for="(citation, index) in message.citations"
          :id="citationAnchorId(index)"
          :key="`card-${citation.docId}-${citation.segmentIndex}`"
          :class="['citation-card', { 'citation-card--highlight': highlightedIndex === index }]"
        >
          <div class="citation-card__head">
            <span class="citation-card__index">[{{ index + 1 }}]</span>
            <span class="citation-card__doc">{{ citation.docName }}</span>
            <span class="citation-card__meta">第 {{ citation.segmentIndex }} 段</span>
            <span v-if="hasPage(citation)" class="citation-card__meta">第 {{ citation.page }} 页</span>
            <span class="citation-card__score">分数 {{ formatScore(citation.score) }}</span>
          </div>
          <p class="citation-card__excerpt">{{ citation.excerpt }}</p>
        </li>
      </ol>
    </section>

    <div v-if="!isUser && !message.isWelcome" class="message__footer">
      <span v-if="message.datasetId">知识库 {{ message.datasetId }}</span>
      <span v-if="showCitations">引用 {{ message.citations.length }} 条</span>
      <span v-if="message.firstTokenMs">首字 {{ (message.firstTokenMs / 1000).toFixed(2) }}s</span>
      <span v-if="message.traceId">traceId: {{ message.traceId }}</span>
      <span v-if="message.status === 'stopped'">已停止生成</span>
    </div>
  </article>
</template>
