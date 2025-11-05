<script lang="ts" setup>
import { consoleApiClient } from '@halo-dev/api-client'
import { Toast, VAlert, VButton } from '@halo-dev/components'
import { useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

const EXTENSION_POINT_ENABLED_GROUP = 'extensionPointEnabled'
const EXTENSION_POINT_NAME = 'search-engine'
const Opensearch_EXTENSION_DEFINITION_NAME = 'search-engine-Opensearch-x'

const {
  data: value,
  isLoading,
  refetch,
} = useQuery({
  queryKey: ['plugin:Opensearch:extension-point', EXTENSION_POINT_NAME],
  queryFn: async () => {
    const { data: extensionPointEnabled } =
      await consoleApiClient.configMap.system.getSystemConfigByGroup({
        group: EXTENSION_POINT_ENABLED_GROUP,
      })

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const extensionPointValue = (extensionPointEnabled as any)?.[EXTENSION_POINT_NAME]

    // check is array
    if (Array.isArray(extensionPointValue)) {
      return extensionPointValue[0]
    }

    return null
  },
})

const isOpensearchEnabled = computed(() => {
  return value.value === Opensearch_EXTENSION_DEFINITION_NAME
})

const switching = ref(false)

async function handleEnableOpensearch() {
  switching.value = true

  try {
    const { data: extensionPointEnabled } =
      await consoleApiClient.configMap.system.getSystemConfigByGroup({
        group: EXTENSION_POINT_ENABLED_GROUP,
      })

    await consoleApiClient.configMap.system.updateSystemConfigByGroup({
      group: EXTENSION_POINT_ENABLED_GROUP,
      body: {
        ...extensionPointEnabled,
        [EXTENSION_POINT_NAME]: [Opensearch_EXTENSION_DEFINITION_NAME],
      },
    })

    Toast.success('切换成功')
    await refetch()
  } catch (error) {
    console.error('Failed to switch search engine', error)
    Toast.error('切换搜索引擎失败，请重试')
  } finally {
    switching.value = false
  }
}
</script>
<template>
  <div class=":uno: w-full sm:w-96">
    <VAlert
      v-if="!isOpensearchEnabled && !isLoading"
      title="提示"
      description="检测到当前已启用的搜索引擎不是 Opensearch"
    >
      <template #actions>
        <VButton @click="handleEnableOpensearch" size="sm" :loading="switching">
          切换为 Opensearch
        </VButton>
      </template>
    </VAlert>
  </div>
</template>
