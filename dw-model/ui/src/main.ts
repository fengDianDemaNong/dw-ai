import { createApp } from 'vue';
import Antd from 'ant-design-vue';
import App from './App.vue';
import router from './router';
import { consumeBootHash } from './config/product';
import { bootstrapRemote } from './stores/app';
import './index.css';

async function start() {
  consumeBootHash();
  await bootstrapRemote();
  const app = createApp(App);
  app.use(router);
  app.use(Antd);
  app.mount('#app');
}

void start();
