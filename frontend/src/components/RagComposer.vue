<script setup>
import { computed } from 'vue'

const MAX_LENGTH = 1000

const props = defineProps({
  modelValue: {
    type: String,
    default: '',
  },
  datasetId: {
    type: String,
    default: 'B',
  },
  isStreaming: {
    type: Boolean,
    default: false,
  },
  disabled: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits([
  'update:modelValue',
  'update:datasetId',
  'send',
  'stop',
  'example',
])

const localValue = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const canSubmit = computed(() => (
  localValue.value.trim().length > 0
  && localValue.value.length <= MAX_LENGTH
  && !props.isStreaming
  && !props.disabled
))

const placeholder = computed(() => (
  props.disabled
    ? '演示模式不提供 RAG 问答，请连接真实后端后再试。'
    : '输入需要依据知识库回答的问题，例如：2027年元旦放假几天？'
))

function submit() {
  if (!canSubmit.value) {
    return
  }

  emit('send', localValue.value.trim())
}

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}
</script>

<template>
  <div class="chat-composer">
    <div class="composer__top">
      <label class="field-label">
        <span>知识库</span>
        <select
          :value="datasetId"
          class="select-control"
          :disabled="isStreaming || disabled"
          @change="$emit('update:datasetId', $event.target.value)"
        >
          <option value="A">A 库（细粒度切分）</option>
          <option value="B">B 库（段落切分）</option>
        </select>
      </label>

      <span class="rag-dataset-hint">回答只依据所选知识库资料</span>

      <button
        type="button"
        class="link-button"
        :disabled="disabled"
        @click="$emit('example')"
      >
        填入示例
      </button>

      <span :class="['char-count', { 'char-count--over': modelValue.length > MAX_LENGTH }]">
        {{ modelValue.length }} / {{ MAX_LENGTH }}
      </span>
    </div>

    <div class="composer__row">
      <textarea
        v-model="localValue"
        class="chat-input"
        rows="3"
        :placeholder="placeholder"
        :disabled="disabled"
        aria-label="输入知识库问题"
        @keydown="handleKeydown"
      ></textarea>

      <button
        v-if="!isStreaming"
        type="button"
        class="primary-button"
        :disabled="!canSubmit"
        @click="submit"
      >
        发送
      </button>
      <button
        v-else
        type="button"
        class="danger-button"
        @click="$emit('stop')"
      >
        停止
      </button>
    </div>

    <div class="composer__hint">
      <span>Enter 发送，Shift + Enter 换行</span>
      <span v-if="modelValue.length > MAX_LENGTH" class="hint-error">超出 {{ MAX_LENGTH }} 字符</span>
      <span v-else>回答结束后返回引用</span>
    </div>
  </div>
</template>
