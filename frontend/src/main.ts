import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";

import { httpClient } from "@/api";
import { router } from "@/router";
import { useSessionStore } from "@/stores/session";
import "@/styles/tokens.css";
import "@/styles/base.css";

import App from "./App.vue";

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
app.use(ElementPlus, { locale: zhCn });
app.use(router);

const session = useSessionStore(pinia);
httpClient.setUnauthorizedHandler(() => {
  session.markAnonymous();
});

app.mount("#app");
