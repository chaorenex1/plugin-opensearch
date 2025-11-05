import { VLoading } from '@halo-dev/components'
import { definePlugin } from '@halo-dev/console-shared'
import 'uno.css'
import { defineAsyncComponent, markRaw } from 'vue'
import SimpleIconsOpensearch from '~icons/simple-icons/Opensearch?color=#FF5CAA'

export default definePlugin({
  routes: [
    {
      parentName: 'ToolsRoot',
      route: {
        path: 'Opensearch-overview',
        name: 'OpensearchOverview',
        redirect: '/plugins/Opensearch?tab=overview',
        meta: {
          title: 'Opensearch 数据概览',
          description: '查看 Opensearch 搜索引擎的索引数据',
          searchable: true,
          permissions: ['*'],
          menu: {
            name: 'Opensearch 数据概览',
            icon: markRaw(SimpleIconsOpensearch),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {
    'plugin:self:tabs:create': () => {
      return [
        {
          id: 'overview',
          label: '数据概览',
          component: defineAsyncComponent({
            loader: () => import('./components/OverviewTab.vue'),
            loadingComponent: VLoading,
          }),
          permissions: ['*'],
        },
      ]
    },
  },
})
