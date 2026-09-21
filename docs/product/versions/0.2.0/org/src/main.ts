import { createApp } from 'vue';
import Antd from 'ant-design-vue';
import App from './App.vue';
import router from './router';
import { consumeBootHash } from './config/suite';
import { applyBootSession } from './stores/app';
import './index.css';

const boot = consumeBootHash();
if (boot) applyBootSession(boot);

const app = createApp(App);
app.use(router);
app.use(Antd);
app.mount('#app');
router.isReady().then(() => {
  if (window.location.hash.startsWith('#boot=')) {
    history.replaceState(null, '', window.location.pathname + window.location.search);
  }
});
