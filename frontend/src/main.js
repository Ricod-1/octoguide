import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import './styles.css'

// 统一注册 Element Plus，避免自动导入插件与 Vite 版本不兼容导致构建失败。
createApp(App).use(ElementPlus).mount('#app')
