import { createApp } from 'vue';
import Antd from 'ant-design-vue';
import App from './App.vue';
import router from './router';
import { bootstrapRemote } from './stores/app';
import './index.css';

async function start() {
  await bootstrapRemote();
  const app = createApp(App);
  app.use(router);
  app.use(Antd);
  app.mount('#app');
}

void start();
