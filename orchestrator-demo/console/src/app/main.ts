import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import App from './App.vue'
import router from './router'
import './styles/base.css'

// Register every n-* component globally: view templates use Naive UI tags
// without local imports, and the message/dialog providers must exist for
// useMessage()/useDialog() in feature views.
createApp(App).use(createPinia()).use(router).use(naive).mount('#app')
