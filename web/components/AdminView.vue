<script setup lang="ts">
import AdminUserName from "./AdminUserName.vue";
import { provideAdminUserNames } from "../admin-user-names";
import AdminUserSelect from "./AdminUserSelect.vue";
import { ref, computed, onMounted, h, watch } from "vue";
import "../admin.css";
import {
  LayoutDashboard,
  Package,
  Wallet,
  Truck,
  ShoppingBag,
  RotateCcw,
  Banknote,
  Gift,
  BookOpen,
  Users,
  UserCog,
  History,
  ArrowUpRight,
  LogOut,
  Menu,
  ChevronRight,
  RefreshCw,
  Plus,
} from "lucide-vue-next";
import StorageOperations from "./StorageOperations.vue";
import CommerceConfiguration from "./CommerceConfiguration.vue";
import OrderOperations from "./OrderOperations.vue";
import PaymentOperations from "./PaymentOperations.vue";
import AfterSalesOperations from "./AfterSalesOperations.vue";
import CashRefundOperations from "./CashRefundOperations.vue";
import RewardOperations from "./RewardOperations.vue";
import OperationsExports from "./OperationsExports.vue";
import FinanceExports from "./FinanceExports.vue";
import MembershipOperations from "./MembershipOperations.vue";
import WalletOperations from "./WalletOperations.vue";
import BusinessSettings from "./BusinessSettings.vue";
import BrandContentOperations from "./BrandContentOperations.vue";
import CourseRightsOperations from "./CourseRightsOperations.vue";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminFilterBar from "./AdminFilterBar.vue";
import AdminSelect from "./AdminSelect.vue";
import FeedbackToast from "./FeedbackToast.vue";
import {
  auditAction,
  auditActor,
  auditObject,
  auditObjectNames,
  auditReason,
} from "../audit";
import {
  api,
  ApiError,
  permissionNames,
  type Staff,
  type User,
  type Audit,
  type Permission,
} from "../client";
const me = ref<Staff | null>(null),
  loading = ref(true),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const userNames = provideAdminUserNames(() => me.value?.id);
function displayActor(event: Audit) {
  return event.actor_kind === "user" && event.actor_id
    ? userNames.label(event.actor_id, event.actor_name)
    : auditActor(event);
}
function displayObject(event: Audit) {
  return event.object_type === "user" && /^[1-9]\d*$/.test(event.object_id)
    ? userNames.label(event.object_id, event.object_name || undefined)
    : auditObject(event);
}
const refreshKey = ref(0);
const username = ref(""),
  password = ref(""),
  section = ref("overview");
const staff = ref<Staff[]>([]),
  users = ref<User[]>([]),
  events = ref<Audit[]>([]),
  total = ref(0),
  page = ref(1);
const filterType = ref(""),
  filterId = ref(""),
  modal = ref<null | "create" | "edit" | "remove">(null),
  selected = ref<Staff | null>(null);
const form = ref({
  username: "",
  displayName: "",
  password: "",
  permissions: [] as Permission[],
  enabled: true,
  reason: "",
});
const menuExpanded = ref(false);
watch(filterType, () => {
  filterId.value = "";
});
const selectedAudit = ref<Audit | null>(null);
const focusedPickup = ref<{ id: number; number: string }>();
const navigation = [
  {
    key: "storage",
    label: "寄存与提货",
    icon: Truck,
    group: "交易与履约",
    permission: "orders:manage",
    description: "查看寄存台账、审核提货、管理到期与物流。",
  },
  {
    key: "commerce",
    label: "套餐与提醒配置",
    icon: Package,
    group: "品牌经营",
    permission: "catalog:manage",
    description: "配置活动买赠套餐与寄存消息提醒。",
  },
  {
    key: "posts",
    label: "品牌种草内容",
    icon: BookOpen,
    group: "品牌经营",
    permission: "content:manage",
    description: "维护品牌图文与短视频，审核后发布。",
  },
  {
    key: "courses",
    label: "课程成长权益",
    icon: BookOpen,
    group: "品牌经营",
    permission: "content:manage",
    description: "维护分身份课程权益与联系指引。",
  },
  {
    key: "overview",
    label: "工作台",
    icon: LayoutDashboard,
    group: "总览",
    description: "查看工作入口，开始日常经营。",
  },
  {
    key: "catalog",
    label: "商品与分类",
    icon: Package,
    group: "品牌经营",
    permission: "catalog:manage",
    description: "维护商品、规格与身份价格，管理商品上架。",
  },
  {
    key: "content",
    label: "品牌与经营说明",
    icon: BookOpen,
    group: "品牌经营",
    permission: "content:manage",
    description: "维护小程序品牌展示、购买说明与公司收款资料。",
  },
  {
    key: "membership",
    label: "产品包与入驻",
    icon: Users,
    group: "品牌经营",
    permission: "onboarding:manage",
    description: "配置固定权益包，客户扣积分购买后开通身份并安排交付。",
  },
  {
    key: "orders",
    label: "订单与发货",
    icon: ShoppingBag,
    group: "交易与履约",
    permission: "orders:manage",
    description: "核查订单与付款，安排发货并维护物流记录。",
  },
  {
    key: "aftersales",
    label: "商品售后",
    icon: RotateCcw,
    group: "交易与履约",
    permission: "orders:manage",
    description: "受理退货退款，核对商品与原收款或积分记录。",
  },
  {
    key: "shipping",
    label: "配送政策",
    icon: Truck,
    group: "交易与履约",
    permission: "orders:manage",
    description: "设置配送地区、运费与订单付款时限。",
  },
  {
    key: "receipts",
    label: "积分支付记录",
    icon: Banknote,
    group: "资金与奖励",
    permission: "finance:manage",
    description: "查询普通商品和权益包的积分扣款记录；充值在积分管理核款。",
  },
  {
    key: "orderrefunds",
    label: "订单退款登记",
    icon: RotateCcw,
    group: "资金与奖励",
    permission: "finance:manage",
    description: "办理商品原积分退款，并查询历史线下退款记录。",
  },
  {
    key: "wallet",
    label: "积分充值收款",
    icon: Wallet,
    group: "资金与奖励",
    permission: "finance:manage",
    description: "核实公司到账、发放积分，逐笔核对积分来源。",
  },
  {
    key: "cashrefunds",
    label: "未用积分退现金",
    icon: Banknote,
    group: "资金与奖励",
    permission: "finance:manage",
    description: "核对原收款与未用积分，记录实际现金退款。",
  },
  {
    key: "rewards",
    label: "奖励与结算",
    icon: Gift,
    group: "资金与奖励",
    permission: "rewards:manage",
    description: "登记代理奖励，处理月结、抵扣与追回。",
  },
  {
    key: "users",
    label: "用户资料",
    icon: Users,
    group: "协作与记录",
    permission: "users:read",
    description: "查看用户的有效身份与必要联系资料。",
  },
  {
    key: "staff",
    label: "工作人员",
    icon: UserCog,
    group: "协作与记录",
    permission: "staff:manage",
    description: "管理同事的账号、功能权限与启用状态。",
  },
  {
    key: "audit",
    label: "操作记录",
    icon: History,
    group: "协作与记录",
    permission: "audit:read",
    description: "查看谁在什么时候做了什么，以及处理结果和操作原因。",
  },
] as const;
const sections = computed(() =>
  navigation.filter(
    (item) =>
      !("permission" in item) ||
      me.value?.permissions.includes(item.permission),
  ),
);
const sectionGroups = computed(() =>
  ["总览", "交易与履约", "品牌经营", "资金与奖励", "协作与记录"]
    .map((label) => ({
      label,
      items: sections.value
        .filter((item) => item.group === label)
        .sort((a, b) => menuOrder.indexOf(a.key) - menuOrder.indexOf(b.key)),
    }))
    .filter((group) => group.items.length),
);
const menuOrder = [
  "overview",
  "orders",
  "storage",
  "aftersales",
  "shipping",
  "catalog",
  "membership",
  "commerce",
  "posts",
  "courses",
  "content",
  "wallet",
  "receipts",
  "orderrefunds",
  "cashrefunds",
  "rewards",
  "users",
  "staff",
  "audit",
];
const workGroups = computed(() =>
  sectionGroups.value.filter((group) => group.label !== "总览"),
);
const currentSection = computed(
  () =>
    sections.value.find((item) => item.key === section.value) || navigation[0],
);
const quickEntries = computed(() =>
  ["orders", "storage", "wallet", "aftersales", "catalog"].flatMap((key) =>
    sections.value.filter((item) => item.key === key),
  ),
);
const sectionTitle = computed(
  () => sections.value.find((s) => s.key === section.value)?.label || "工作台",
);
function report(e: unknown) {
  notice.value = "";
  error.value = (e as Error).message;
  if (e instanceof ApiError && e.status === 401) {
    me.value = null;
    modal.value = null;
    password.value = "";
  }
}
onMounted(async () => {
  try {
    me.value = await api<Staff>("/admin/me");
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 401)) report(e);
  } finally {
    loading.value = false;
  }
});
async function login() {
  busy.value = true;
  error.value = "";
  try {
    me.value = await api<Staff>("/admin/login", {
      method: "POST",
      body: { username: username.value, password: password.value },
    });
    password.value = "";
    section.value = "overview";
  } catch (e) {
    report(e);
  } finally {
    busy.value = false;
  }
}
async function logout() {
  busy.value = true;
  error.value = "";
  try {
    await api("/admin/logout", { method: "POST" });
    me.value = null;
    password.value = "";
  } catch (e) {
    report(e);
  } finally {
    busy.value = false;
  }
}
async function load() {
  notice.value = "";
  busy.value = true;
  error.value = "";
  try {
    if (section.value === "staff")
      staff.value = await api<Staff[]>("/admin/staff");
    if (section.value === "users") {
      const result = await api<{ items: User[]; total: number }>(
        `/admin/users?page=${page.value}`,
      );
      users.value = result.items;
      total.value = result.total;
    }
    if (section.value === "audit") {
      const query = new URLSearchParams({ page: String(page.value) });
      if (filterType.value) query.set("objectType", filterType.value);
      if (filterId.value) query.set("objectId", filterId.value);
      const result = await api<{ items: Audit[]; total: number }>(
        `/admin/audit?${query}`,
      );
      events.value = result.items;
      total.value = result.total;
    }
  } catch (e) {
    report(e);
  } finally {
    busy.value = false;
  }
}
async function navigate(key: string) {
  section.value = key;
  menuExpanded.value = false;
  page.value = 1;
  notice.value = "";
  error.value = "";
  await load();
}
function open(mode: "create" | "edit" | "remove", value?: Staff) {
  notice.value = "";
  selected.value = value || null;
  form.value = {
    username: value?.username || "",
    displayName: value?.displayName || "",
    password: "",
    permissions: [...(value?.permissions || [])],
    enabled: value?.enabled ?? true,
    reason: "",
  };
  error.value = "";
  modal.value = mode;
}
async function save() {
  notice.value = "";
  busy.value = true;
  error.value = "";
  try {
    const f = form.value;
    if (modal.value === "create")
      await api("/admin/staff", {
        method: "POST",
        body: {
          username: f.username,
          displayName: f.displayName,
          password: f.password,
          permissions: f.permissions,
          reason: f.reason,
        },
      });
    else if (modal.value === "remove")
      await api(`/admin/staff/${selected.value!.id}/remove`, {
        method: "POST",
        body: { reason: f.reason },
      });
    else
      await api(`/admin/staff/${selected.value!.id}`, {
        method: "PATCH",
        body: {
          displayName: f.displayName,
          permissions: f.permissions,
          enabled: f.enabled,
          reason: f.reason,
        },
      });
    const message =
      modal.value === "remove"
        ? "工作人员已移除，历史操作记录已保留"
        : "工作人员设置已保存";
    modal.value = null;
    me.value = await api<Staff>("/admin/me");
    if (!me.value.permissions.includes("staff:manage"))
      section.value = "overview";
    else await load();
    if (!error.value) notice.value = message;
  } catch (e) {
    report(e);
  } finally {
    busy.value = false;
  }
}
const menuItems = computed(() =>
  sectionGroups.value.map((group) => ({
    key: group.label,
    type: "group",
    label: group.label === "总览" ? undefined : group.label,
    children: group.items.map((item) => ({
      key: item.key,
      label: item.label,
      icon: () => h(item.icon, { size: 17, "aria-hidden": true }),
    })),
  })),
);
const staffSearch = ref("");
const filteredStaff = computed(() =>
  staff.value.filter((person) =>
    `${person.displayName} ${person.username}`
      .toLowerCase()
      .includes(staffSearch.value.toLowerCase().trim()),
  ),
);
const staffColumns = [
  { title: "工作人员", key: "person", width: 160 },
  { title: "账号", dataIndex: "username", key: "username", width: 150 },
  { title: "已授权功能", key: "permissions", width: 330 },
  { title: "状态", key: "status", width: 100 },
  { title: "操作", key: "actions", width: 190 },
];
const userColumns = [
  { title: "用户 / 编号", key: "id", width: 230 },
  { title: "有效身份", key: "identity", width: 160 },
  { title: "联系电话", key: "phone", width: 180 },
];
const auditColumns = [
  { title: "操作时间", key: "time", width: 180 },
  { title: "操作者", key: "actor", width: 150 },
  { title: "做了什么", key: "action", width: 240 },
  { title: "操作对象", key: "object", width: 190 },
  { title: "处理结果与说明", key: "result", width: 300 },
  { title: "详情", key: "detail", width: 100 },
];
const permissionOptions = Object.entries(permissionNames).map(
  ([value, label]) => ({ value, label }),
);
async function changePage(value: number) {
  page.value = value;
  await load();
}
</script>
<template>
  <FeedbackToast v-model:notice="notice" v-model:error="error" />
  <div v-if="loading" class="loading-screen">
    <a-spin size="large" tip="正在确认登录状态…" />
  </div>
  <main v-else-if="!me" class="admin-login">
    <aside class="login-story">
      <img
        class="brand-logo-image"
        src="/brand/logo-horizontal-red.svg"
        alt="清欣简妍肌"
        width="680"
        height="200"
      />
      <div>
        <span class="login-caption">品牌经营 · 有序协作</span>
        <h1>让每一份关照，<br />都有清楚的记录。</h1>
        <p>商品、代理、收款与履约，<br />在同一个工作空间里有序开展。</p>
      </div>
      <small>QINGXIN · BRAND OPERATIONS</small>
    </aside>
    <section class="login-form-wrap">
      <AdminForm class="login-form" @submit="login">
        <div class="login-title">
          <h2>登录管理后台</h2>
          <p>使用品牌方授权的工作人员账号</p>
        </div>
        <label
          >工作人员账号<a-input
            v-model:value="username"
            name="username"
            autocomplete="username"
            required
            :maxlength="32"
            placeholder="请输入账号"
            size="large"
        /></label>
        <label
          >登录密码<a-input-password
            v-model:value="password"
            name="password"
            autocomplete="current-password"
            required
            :maxlength="128"
            placeholder="请输入密码"
            size="large"
        /></label>
        <a-button
          type="primary"
          html-type="submit"
          size="large"
          block
          :loading="busy"
          >进入管理后台</a-button
        >
        <small class="muted">账号尚未开通？请联系品牌管理员。</small>
      </AdminForm>
    </section>
  </main>
  <a-layout v-else class="admin-shell">
    <a-layout-sider class="sidebar" theme="light" :width="216">
      <div class="sidebar-brand">
        <img
          class="brand-logo-image"
          src="/brand/logo-horizontal-red.svg"
          alt="清欣简妍肌"
          width="680"
          height="200"
        /><span>品牌方管理后台</span>
      </div>
      <a-menu
        id="admin-navigation"
        class="workspace-menu"
        mode="inline"
        :items="menuItems"
        :selected-keys="[section]"
        @click="({ key }: any) => navigate(String(key))"
        aria-label="后台功能"
      />
      <div class="sidebar-bottom">
        <a-avatar :size="34">{{ me.displayName.slice(0, 1) }}</a-avatar>
        <div>
          <strong>{{ me.displayName }}</strong
          ><small>{{ me.username }}</small>
        </div>
        <a-button
          type="text"
          aria-label="退出登录"
          :loading="busy"
          @click="logout"
          ><LogOut :size="17"
        /></a-button>
      </div>
    </a-layout-sider>
    <a-drawer
      v-model:open="menuExpanded"
      title="功能导航"
      placement="left"
      :width="280"
      class="admin-navigation-drawer"
      ><a-menu
        mode="inline"
        :items="menuItems"
        :selected-keys="[section]"
        @click="({ key }: any) => navigate(String(key))"
    /></a-drawer>
    <a-layout class="admin-main">
      <a-layout-header class="admin-topbar">
        <a-button
          type="text"
          class="mobile-menu"
          aria-label="展开功能菜单"
          @click="menuExpanded = true"
          ><Menu :size="20"
        /></a-button>
        <a-breadcrumb
          ><a-breadcrumb-item
            ><a @click="navigate('overview')">商城管理</a></a-breadcrumb-item
          ><a-breadcrumb-item>{{
            sectionTitle
          }}</a-breadcrumb-item></a-breadcrumb
        >
        <a-space class="admin-page-actions" wrap>
          <div id="admin-page-actions"></div>
          <a-button
            v-if="section === 'staff'"
            type="primary"
            @click="open('create')"
            ><template #icon><Plus :size="16" /></template
            >新增工作人员</a-button
          >
          <a-button
            v-else-if="section !== 'overview'"
            :loading="busy"
            @click="
              ['users', 'staff', 'audit'].includes(section)
                ? load()
                : refreshKey++
            "
            ><template #icon><RefreshCw :size="14" /></template
            >刷新记录</a-button
          >
          <span v-else class="date-label">{{
            new Date().toLocaleDateString("zh-CN", {
              year: "numeric",
              month: "long",
              day: "numeric",
            })
          }}</span>
        </a-space>
        <span class="workspace-badge">{{ me.displayName }}</span>
      </a-layout-header>
      <a-layout-content class="admin-content">
        <template v-if="section === 'overview'">
          <a-card
            v-if="quickEntries.length"
            title="常用工作"
            class="quick-work"
            :bordered="false"
          >
            <div class="quick-work-grid">
              <a-button
                v-for="item in quickEntries"
                :key="item.key"
                class="work-shortcut"
                @click="navigate(item.key)"
                ><component :is="item.icon" :size="22" /><span>{{
                  item.label
                }}</span
                ><ArrowUpRight :size="16"
              /></a-button>
            </div>
          </a-card>
          <div class="work-directory">
            <a-card
              v-for="group in workGroups"
              :key="group.label"
              :title="group.label"
              class="directory-group"
              :bordered="false"
              ><a-list :data-source="group.items"
                ><template #renderItem="{ item }"
                  ><a-list-item
                    ><a-button
                      type="text"
                      class="directory-button"
                      @click="navigate(item.key)"
                      ><span class="directory-icon"
                        ><component :is="item.icon" :size="19" /></span
                      ><span class="directory-copy"
                        ><strong>{{ item.label }}</strong
                        ><small>{{ item.description }}</small></span
                      ><ChevronRight
                        :size="
                          16
                        " /></a-button></a-list-item></template></a-list
            ></a-card>
          </div>
        </template>
        <a-card
          v-else-if="section === 'staff'"
          :bordered="false"
          class="table-panel"
        >
          <div class="panel-toolbar">
            <strong>{{ staff.length }} 个工作人员账号</strong
            ><a-input-search
              v-model:value="staffSearch"
              allow-clear
              placeholder="搜索姓名或账号"
              aria-label="搜索工作人员"
              class="staff-search"
            />
          </div>
          <a-table
            :columns="staffColumns"
            :data-source="filteredStaff"
            row-key="id"
            :loading="busy"
            :scroll="{ x: 930 }"
            :pagination="{
              pageSize: 10,
              showSizeChanger: false,
              hideOnSinglePage: false,
            }"
            :locale="{ emptyText: '暂无符合条件的工作人员' }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'person'"
                ><strong>{{ record.displayName }}</strong
                ><a-tag v-if="record.id === me.id" color="processing"
                  >当前账号</a-tag
                ></template
              >
              <template v-else-if="column.key === 'permissions'"
                ><a-space size="small" wrap
                  ><a-tag
                    v-for="permission in record.permissions as Permission[]"
                    :key="permission"
                    >{{ permissionNames[permission] }}</a-tag
                  ><span v-if="!record.permissions.length" class="muted"
                    >尚未授权</span
                  ></a-space
                ></template
              >
              <a-badge
                v-else-if="column.key === 'status'"
                :status="record.enabled ? 'success' : 'default'"
                :text="record.enabled ? '已启用' : '已停用'"
              />
              <a-space v-else-if="column.key === 'actions'" wrap
                ><a-button size="small" @click="open('edit', record as Staff)"
                  >授权 / 状态</a-button
                ><a-button
                  size="small"
                  danger
                  @click="open('remove', record as Staff)"
                  >移除</a-button
                ></a-space
              >
            </template>
          </a-table>
        </a-card>
        <a-card
          v-else-if="section === 'users'"
          :bordered="false"
          class="table-panel"
        >
          <div class="panel-toolbar">
            <strong>用户资料</strong
            ><span class="muted">共 {{ total }} 位用户</span>
          </div>
          <a-table
            :columns="userColumns"
            :data-source="users"
            row-key="id"
            :loading="busy"
            :pagination="false"
            :scroll="{ x: 680 }"
            :locale="{ emptyText: '还没有用户登录' }"
            ><template #bodyCell="{ column, record }"
              ><AdminUserName
                v-if="column.key === 'id'"
                :id="record.id"
                :name="record.nickname"
              />
              <a-tag v-else-if="column.key === 'identity'">{{
                record.identityName
              }}</a-tag
              ><span v-else-if="column.key === 'phone'">{{
                record.phone || "尚未填写"
              }}</span></template
            ></a-table
          >
        </a-card>
        <a-card
          v-else-if="section === 'audit'"
          :bordered="false"
          class="table-panel"
        >
          <AdminFilterBar
            @submit="
              page = 1;
              load();
            "
            ><label
              >业务对象<AdminSelect v-model="filterType"
                ><option value="">全部对象</option>
                <option
                  v-for="(label, value) in auditObjectNames"
                  :key="value"
                  :value="value"
                >
                  {{ label }}
                </option></AdminSelect
              ></label
            ><label
              >对象编号<AdminUserSelect
                v-if="filterType === 'user'"
                v-model="filterId" /><a-input
                v-else
                v-model:value="filterId"
                :maxlength="40"
                placeholder="请输入对象编号" /></label
            ><template #actions
              ><a-button html-type="submit" type="primary" :loading="busy"
                >查找记录</a-button
              ><a-button
                @click="
                  filterType = '';
                  filterId = '';
                  page = 1;
                  load();
                "
                >重置</a-button
              ></template
            ></AdminFilterBar
          >
          <a-table
            :columns="auditColumns"
            :data-source="events"
            row-key="id"
            :loading="busy"
            :pagination="false"
            :scroll="{ x: 1260 }"
            :locale="{ emptyText: '没有符合条件的记录' }"
            ><template #bodyCell="{ column, record }"
              ><span v-if="column.key === 'time'" class="mono">{{
                new Date(record.create_time).toLocaleString("zh-CN")
              }}</span
              ><template v-else-if="column.key === 'actor'">{{
                displayActor(record)
              }}</template
              ><template v-else-if="column.key === 'action'"
                ><strong>{{ auditAction(record) }}</strong></template
              ><template v-else-if="column.key === 'object'">{{
                displayObject(record)
              }}</template
              ><template v-else-if="column.key === 'result'"
                ><a-tag
                  :color="
                    record.result === 'success'
                      ? 'success'
                      : record.result === 'denied'
                        ? 'warning'
                        : 'error'
                  "
                  >{{
                    record.result === "success"
                      ? "成功"
                      : record.result === "denied"
                        ? "已拦截"
                        : "失败"
                  }}</a-tag
                >
                <p class="audit-reason">
                  <span class="muted">{{
                    record.result === "success" ? "说明：" : "原因："
                  }}</span
                  >{{ auditReason(record) }}
                </p></template
              ><template v-else-if="column.key === 'detail'"
                ><a-button
                  type="link"
                  size="small"
                  @click="selectedAudit = record"
                  >查看详情</a-button
                ></template
              ></template
            ></a-table
          >
        </a-card>
        <a-pagination
          v-if="['users', 'audit'].includes(section)"
          class="pagination"
          :current="page"
          :total="total"
          :page-size="30"
          :show-size-changer="false"
          :show-total="(count: number) => `共 ${count} 条`"
          :disabled="busy"
          @change="changePage"
        />
        <StorageOperations
          v-if="section === 'storage'"
          :staff="me"
          :pickup="focusedPickup"
        />
        <CommerceConfiguration v-if="section === 'commerce'" :staff="me" />
        <OrderOperations
          v-if="section === 'shipping'"
          :key="'shipping:' + refreshKey"
          section="shipping"
        />
        <PaymentOperations
          v-if="['orders', 'receipts'].includes(section)"
          :key="section + ':' + refreshKey"
          :finance-only="section === 'receipts'"
          :can-finance="me.permissions.includes('finance:manage')"
          @open-pickup="
            (pickup) => {
              focusedPickup = pickup;
              section = 'storage';
            }
          "
        />
        <AfterSalesOperations
          v-if="['aftersales', 'orderrefunds'].includes(section)"
          :key="section + ':' + refreshKey"
          :can-manage="
            section === 'aftersales' && me.permissions.includes('orders:manage')
          "
          :canFinance="me.permissions.includes('finance:manage')"
        />
        <CashRefundOperations
          v-if="section === 'cashrefunds'"
          :key="'cash:' + refreshKey"
        />
        <RewardOperations
          v-if="section === 'rewards'"
          :key="'rewards:' + refreshKey"
        />
        <MembershipOperations
          v-if="section === 'membership'"
          :key="'membership:' + refreshKey"
          :permissions="me.permissions"
        />
        <WalletOperations
          v-if="section === 'wallet'"
          :key="'wallet:' + refreshKey"
        />
        <BrandContentOperations
          v-if="section === 'posts'"
          :key="'posts:' + refreshKey"
        />
        <CourseRightsOperations
          v-if="section === 'courses'"
          :key="'courses:' + refreshKey"
        />
        <BusinessSettings
          v-if="['content', 'catalog'].includes(section)"
          :key="section + refreshKey"
          :section="section"
        />
        <OperationsExports
          v-if="['membership', 'orders', 'aftersales'].includes(section)"
          :permissions="me.permissions"
        />
        <FinanceExports
          v-if="
            [
              'wallet',
              'receipts',
              'orderrefunds',
              'cashrefunds',
              'orders',
              'aftersales',
              'rewards',
            ].includes(section)
          "
          :permissions="me.permissions"
        />
      </a-layout-content>
      <a-layout-footer class="admin-footer"
        >清欣简妍肌 · 品牌方管理后台</a-layout-footer
      >
    </a-layout>
    <AdminDialog
      v-if="selectedAudit"
      drawer
      title="操作记录详情"
      @close="selectedAudit = null"
    >
      <dl class="audit-detail">
        <div>
          <dt>操作时间</dt>
          <dd>
            {{ new Date(selectedAudit.create_time).toLocaleString("zh-CN") }}
          </dd>
        </div>
        <div>
          <dt>操作者</dt>
          <dd>{{ displayActor(selectedAudit) }}</dd>
        </div>
        <div>
          <dt>操作内容</dt>
          <dd>{{ auditAction(selectedAudit) }}</dd>
        </div>
        <div>
          <dt>操作对象</dt>
          <dd>{{ displayObject(selectedAudit) }}</dd>
        </div>
        <div>
          <dt>处理结果</dt>
          <dd>
            {{
              selectedAudit.result === "success"
                ? "成功"
                : selectedAudit.result === "denied"
                  ? "已拦截"
                  : "失败"
            }}
          </dd>
        </div>
        <div>
          <dt>操作原因</dt>
          <dd>{{ selectedAudit.reason || "未填写" }}</dd>
        </div>
      </dl>
      <p
        v-if="/\.(update|save|settings)$/.test(selectedAudit.action)"
        class="field-help"
      >
        本条记录未保存字段修改前后的对比，无法还原具体修改项。
      </p>
      <details class="audit-technical">
        <summary>排查信息</summary>
        <dl class="audit-detail">
          <div>
            <dt>记录编号</dt>
            <dd>{{ selectedAudit.id }}</dd>
          </div>
          <div>
            <dt>操作者编号</dt>
            <dd>
              {{ selectedAudit.actor_kind }} ·
              <AdminUserName
                v-if="selectedAudit.actor_kind === 'user'"
                :id="selectedAudit.actor_id"
                :name="selectedAudit.actor_name"
              /><template v-else>{{ selectedAudit.actor_id }}</template>
            </dd>
          </div>
          <div>
            <dt>操作编码</dt>
            <dd>{{ selectedAudit.action }}</dd>
          </div>
          <div>
            <dt>对象编码</dt>
            <dd>
              {{ selectedAudit.object_type }} ·
              <AdminUserName
                v-if="selectedAudit.object_type === 'user'"
                :id="selectedAudit.object_id"
                :name="selectedAudit.object_name || undefined"
              /><template v-else>{{ selectedAudit.object_id }}</template>
            </dd>
          </div>
          <div>
            <dt>排查编号</dt>
            <dd class="mono">{{ selectedAudit.request_id }}</dd>
          </div>
        </dl>
      </details>
      <div class="dialog-actions">
        <a-button @click="selectedAudit = null">关闭</a-button>
      </div>
    </AdminDialog>
    <AdminDialog
      v-if="modal"
      :drawer="modal !== 'remove'"
      :title="
        modal === 'create'
          ? '新增工作人员'
          : modal === 'remove'
            ? '移除工作人员'
            : '修改授权与状态'
      "
      :busy="busy"
      @close="modal = null"
    >
      <AdminForm @submit="save">
        <template v-if="modal !== 'remove'">
          <label v-if="modal === 'create'"
            >登录账号<a-input
              v-model:value="form.username"
              required
              pattern="[a-zA-Z][a-zA-Z0-9_\-]{2,31}"
              data-error-message="账号须以字母开头，使用 3～32 位字母、数字、下划线或短横线"
              :maxlength="32"
              autocomplete="off"
              placeholder="3～32 位字母、数字、下划线"
          /></label>
          <label
            >工作人员姓名<a-input
              v-model:value="form.displayName"
              required
              :maxlength="30"
          /></label>
          <label v-if="modal === 'create'"
            >初始密码<a-input-password
              v-model:value="form.password"
              required
              minlength="12"
              :maxlength="128"
              autocomplete="new-password"
              placeholder="至少 12 位"
          /></label>
          <fieldset class="permission-fieldset">
            <legend>功能权限</legend>
            <a-checkbox-group
              v-model:value="form.permissions"
              :options="permissionOptions"
              class="permission-grid"
            />
          </fieldset>
          <a-checkbox v-if="modal === 'edit'" v-model:checked="form.enabled"
            >启用该账号</a-checkbox
          >
        </template>
        <p v-else class="field-help">
          移除「{{
            selected?.displayName
          }}」后，该账号将无法登录，历史操作记录会保留。
        </p>
        <label
          >操作原因<a-textarea
            v-model:value="form.reason"
            required
            :maxlength="200"
            :auto-size="{ minRows: 3, maxRows: 5 }"
            placeholder="说明本次新增、授权、停用或移除的原因"
        /></label>
        <div class="dialog-actions">
          <a-button :disabled="busy" @click="modal = null">取消</a-button
          ><a-button
            type="primary"
            :danger="modal === 'remove'"
            html-type="submit"
            :loading="busy"
            >{{ modal === "remove" ? "确认移除" : "保存设置" }}</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
  </a-layout>
</template>
