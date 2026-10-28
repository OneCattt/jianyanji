import { createApp, h } from "vue";
import {
  Avatar,
  Badge,
  Breadcrumb,
  Button,
  Card,
  Checkbox,
  Collapse,
  ConfigProvider,
  Descriptions,
  Drawer,
  Input,
  InputNumber,
  Layout,
  List,
  Menu,
  Pagination,
  Progress,
  Space,
  Spin,
  Steps,
  Table,
  Tabs,
  Tag,
  Upload,
} from "ant-design-vue";
import dayjs from "dayjs";
import "dayjs/locale/zh-cn";
import zhCN from "ant-design-vue/es/locale/zh_CN";
import "ant-design-vue/dist/reset.css";
import App from "./App.vue";
import { adminTheme } from "./admin-theme";
import "./style.css";

dayjs.locale("zh-cn");

const app = createApp({
  render: () =>
    h(
      ConfigProvider,
      {
        locale: zhCN,
        autoInsertSpaceInButton: false,
        theme: adminTheme,
      },
      { default: () => h(App) },
    ),
});

// 按实际使用的组件注册，避免引入整套组件库。
for (const component of [
  Avatar,
  Badge,
  Breadcrumb,
  Button,
  Card,
  Checkbox,
  Collapse,
  Descriptions,
  Drawer,
  Input,
  InputNumber,
  Layout,
  List,
  Menu,
  Pagination,
  Progress,
  Space,
  Spin,
  Steps,
  Table,
  Tabs,
  Tag,
  Upload,
])
  app.use(component);
app.mount("#app");
