import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { appStore } from './stores/app'
import './assets/styles/main.css'

createApp(App).provide('appStore', appStore).use(router).mount('#app')
