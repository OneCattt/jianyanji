<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
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
  X,
  ChevronRight,
  RefreshCw,
  Plus,
  ShieldCheck,
} from "lucide-vue-next";
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
import AdminSelect from "./AdminSelect.vue";
import FeedbackToast from "./FeedbackToast.vue";
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
const navigation = [
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
    description: "配置入驻产品包，核实到账后激活身份并安排交付。",
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
    description: "受理退货退款，核对商品与原支付记录。",
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
    key: "wallet",
    label: "公司收款与积分",
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
    description: "按业务对象核对操作者、处理结果与原因。",
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
  ["总览", "品牌经营", "交易与履约", "资金与奖励", "协作与记录"]
    .map((label) => ({
      label,
      items: sections.value.filter((item) => item.group === label),
    }))
    .filter((group) => group.items.length),
);
const workGroups = computed(() =>
  sectionGroups.value.filter((group) => group.label !== "总览"),
);
const currentSection = computed(
  () =>
    sections.value.find((item) => item.key === section.value) || navigation[0],
);
const quickEntries = computed(() =>
  sections.value.filter((item) =>
    ["catalog", "orders", "membership", "wallet"].includes(item.key),
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
async function turnPage(delta: number) {
  page.value += delta;
  await load();
}
const actionNames: Record<string, string> = {
  "user.login": "用户登录",
  "user.logout": "用户退出",
  "profile.update": "修改个人资料",
  "profile.denied": "拦截资料越权",
  "staff.login": "后台登录",
  "staff.logout": "后台退出",
  "staff.create": "新增工作人员",
  "staff.update": "变更授权或状态",
  "staff.remove": "移除工作人员",
  "staff.seed": "初始化演示账号",
  "staff.bootstrap": "初始化管理员",
  "permission.denied": "拦截功能越权",
  "request.denied": "拦截不允许的操作",
};
</script>
<template>
  <div v-if="loading" class="loading-screen">正在确认登录状态…</div>
  <main v-else-if="!me" class="admin-login">
    <aside class="login-story">
      <a href="/" class="brand-word">清欣<span>简妍肌</span></a>
      <div>
        <span class="eyebrow">品牌方管理后台</span>
        <h1>有序经营，<br />让关照更从容。</h1>
        <p>从每一位用户，到每一次协作。<br />清楚记录，安心开展。</p>
      </div>
      <span class="login-footer">QINGXIN · BRAND OPERATIONS</span>
      <div class="login-orbit" aria-hidden="true"></div>
    </aside>
    <section class="login-form-wrap">
      <AdminForm class="login-form" @submit="login">
        <span class="eyebrow">欢迎回来</span>
        <h2>登录品牌方后台</h2>
        <p>使用已授权的工作人员账号登录。</p>
        <label
          >工作人员账号<input
            v-model="username"
            name="username"
            autocomplete="username"
            required
            maxlength="32"
            placeholder="请输入账号" /></label
        ><label
          >登录密码<input
            v-model="password"
            name="password"
            type="password"
            autocomplete="current-password"
            required
            maxlength="128"
            placeholder="请输入密码"
        /></label>
        <p v-if="error" class="message error" role="alert">{{ error }}</p>
        <button class="primary full" :disabled="busy">
          {{ busy ? "正在登录…" : "进入管理后台" }} <span>↗</span></button
        ><a class="back-link" href="/">← 返回清欣简妍肌</a
        ><small>账号尚未开通？请联系品牌管理员。</small>
      </AdminForm>
    </section>
  </main>
  <div v-else class="admin-shell">
    <aside class="sidebar" :class="{ 'menu-expanded': menuExpanded }">
      <div class="sidebar-brand">
        <a href="/" class="brand-word">清欣<span>简妍肌</span></a>
        <button
          class="mobile-menu icon-button"
          :aria-expanded="menuExpanded"
          aria-controls="admin-navigation"
          :aria-label="menuExpanded ? '收起功能菜单' : '展开功能菜单'"
          @click="menuExpanded = !menuExpanded"
        >
          <X v-if="menuExpanded" :size="20" /><Menu v-else :size="20" />
        </button>
      </div>
      <span class="workspace-label">品牌方管理后台</span>
      <nav id="admin-navigation" aria-label="后台功能">
        <div
          v-for="group in sectionGroups"
          :key="group.label"
          class="nav-group"
        >
          <p v-if="group.label !== '总览'" class="nav-group-label">
            {{ group.label }}
          </p>
          <button
            v-for="item in group.items"
            :key="item.key"
            :aria-current="section === item.key ? 'page' : undefined"
            @click="navigate(item.key)"
          >
            <component
              :is="item.icon"
              :size="17"
              :stroke-width="1.7"
              aria-hidden="true"
            />
            <span>{{ item.label }}</span>
          </button>
        </div>
      </nav>
      <div class="sidebar-bottom">
        <span class="staff-avatar">{{ me.displayName.slice(0, 1) }}</span>
        <div>
          <strong>{{ me.displayName }}</strong
          ><small>{{ me.username }}</small>
        </div>
        <button aria-label="退出登录" @click="logout" :disabled="busy">
          <LogOut :size="17" aria-hidden="true" />
        </button>
      </div>
    </aside>
    <div class="admin-main">
      <header class="admin-topbar">
        <div class="breadcrumb">
          <span>品牌方工作空间</span
          ><ChevronRight :size="14" aria-hidden="true" /><strong>{{
            sectionTitle
          }}</strong>
        </div>
        <a href="/" target="_blank" rel="noopener"
          >查看小程序体验 <ArrowUpRight :size="14" aria-hidden="true"
        /></a>
      </header>
      <main class="admin-content">
        <div class="admin-heading">
          <div>
            <h1>{{ section === "overview" ? "经营工作台" : sectionTitle }}</h1>
            <p>
              {{
                section === "overview"
                  ? `你好，${me.displayName}。从这里开始今天的品牌工作。`
                  : currentSection.description
              }}
            </p>
          </div>
          <div class="admin-page-actions">
            <div id="admin-page-actions"></div>
            <button
              v-if="section === 'staff'"
              class="primary"
              @click="open('create')"
            >
              <Plus :size="16" aria-hidden="true" /> 新增工作人员</button
            ><button
              v-else-if="section !== 'overview'"
              class="secondary"
              @click="
                ['users', 'staff', 'audit'].includes(section)
                  ? load()
                  : refreshKey++
              "
              :disabled="busy"
            >
              <RefreshCw :size="14" aria-hidden="true" /> 刷新记录</button
            ><span v-else class="date-label">{{
              new Date().toLocaleDateString("zh-CN", {
                year: "numeric",
                month: "long",
                day: "numeric",
              })
            }}</span>
          </div>
        </div>
        <FeedbackToast
          v-model:notice="notice"
          v-model:error="error"
          :show-error="!modal"
        />
        <template v-if="section === 'overview'">
          <section
            v-if="quickEntries.length"
            class="quick-work"
            aria-labelledby="quick-work-title"
          >
            <div class="overview-section-title">
              <h2 id="quick-work-title">常用工作</h2>
              <span>日常业务，直接进入</span>
            </div>
            <div class="quick-work-grid">
              <button
                v-for="item in quickEntries"
                :key="item.key"
                @click="navigate(item.key)"
              >
                <component
                  :is="item.icon"
                  :size="21"
                  :stroke-width="1.6"
                  aria-hidden="true"
                />
                <span>{{
                  item.key === "catalog"
                    ? "管理与上架商品"
                    : item.key === "orders"
                      ? "处理订单与发货"
                      : item.key === "membership"
                        ? "核实入驻与交付"
                        : "核实收款与积分"
                }}</span>
                <ArrowUpRight
                  :size="16"
                  class="quick-arrow"
                  aria-hidden="true"
                />
              </button>
            </div>
          </section>
          <div class="workspace-grid">
            <section
              class="work-directory"
              aria-labelledby="work-directory-title"
            >
              <div class="overview-section-title">
                <h2 id="work-directory-title">业务工作区</h2>
                <span>{{ sections.length - 1 }} 项已授权功能</span>
              </div>
              <div
                v-for="group in workGroups"
                :key="group.label"
                class="directory-group"
              >
                <h3>{{ group.label }}</h3>
                <button
                  v-for="item in group.items"
                  :key="item.key"
                  @click="navigate(item.key)"
                >
                  <span class="directory-icon"
                    ><component
                      :is="item.icon"
                      :size="19"
                      :stroke-width="1.6"
                      aria-hidden="true"
                  /></span>
                  <span class="directory-copy"
                    ><strong>{{ item.label }}</strong
                    ><small>{{ item.description }}</small></span
                  >
                  <ChevronRight :size="16" aria-hidden="true" />
                </button>
              </div>
              <p v-if="!workGroups.length" class="empty-cell">
                当前尚未分配业务权限，请联系管理员开通。
              </p>
            </section>
            <aside class="work-guide">
              <section class="guide-panel">
                <div class="guide-heading">
                  <ShieldCheck :size="18" aria-hidden="true" />
                  <h2>当前工作账号</h2>
                </div>
                <div class="account-summary">
                  <span class="staff-avatar">{{
                    me.displayName.slice(0, 1)
                  }}</span>
                  <div>
                    <strong>{{ me.displayName }}</strong
                    ><small>{{ me.username }}</small>
                  </div>
                </div>
                <dl>
                  <div>
                    <dt>工作身份</dt>
                    <dd>品牌方工作人员</dd>
                  </div>
                  <div>
                    <dt>已授权功能</dt>
                    <dd>{{ sections.length - 1 }} 项</dd>
                  </div>
                </dl>
                <p>需要其他功能？请联系品牌管理员调整账号权限。</p>
              </section>
              <section class="guide-panel processing-guide">
                <h2>业务处理提醒</h2>
                <div>
                  <span class="guide-dot"></span><strong>公司收款</strong>
                  <p>先核实实际到账，再向对应代理发放积分。</p>
                </div>
                <div>
                  <span class="guide-dot"></span><strong>代理入驻</strong>
                  <p>产品包货款用于实物交付，不另发等额订货积分。</p>
                </div>
                <div>
                  <span class="guide-dot"></span><strong>奖励结算</strong>
                  <p>核对收货与售后情况，记录公司实际转账凭证。</p>
                </div>
              </section>
              <p class="workspace-footnote">
                关键业务操作保留处理人和原因，便于后续核对。
              </p>
            </aside>
          </div>
        </template>
        <section v-else-if="section === 'staff'" class="table-panel">
          <div class="panel-title">
            <span>{{ staff.length }} 个账号</span>
          </div>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>工作人员</th>
                  <th>账号</th>
                  <th>已授权功能</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="person in staff" :key="person.id">
                  <td>
                    <strong>{{ person.displayName }}</strong
                    ><small v-if="person.id === me.id"> · 当前账号</small>
                  </td>
                  <td class="mono">{{ person.username }}</td>
                  <td>
                    <div class="tags">
                      <span
                        v-for="permission in person.permissions"
                        :key="permission"
                        class="tag"
                        >{{ permissionNames[permission] }}</span
                      ><span v-if="!person.permissions.length" class="muted"
                        >尚未授权</span
                      >
                    </div>
                  </td>
                  <td>
                    <span
                      :class="[
                        'status-dot',
                        person.enabled ? 'enabled' : 'disabled',
                      ]"
                      >{{ person.enabled ? "已启用" : "已停用" }}</span
                    >
                  </td>
                  <td>
                    <div class="row-actions">
                      <button @click="open('edit', person)">授权 / 状态</button
                      ><button
                        class="danger-text"
                        @click="open('remove', person)"
                      >
                        移除
                      </button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!staff.length">
                  <td colspan="5" class="empty-cell">
                    {{ busy ? "正在读取…" : "暂无工作人员" }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <section v-else-if="section === 'users'" class="table-panel">
          <div class="panel-title">
            <span>{{ total }} 位用户</span>
          </div>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>用户编号</th>
                  <th>昵称</th>
                  <th>有效身份</th>
                  <th>联系电话</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="u in users" :key="u.id">
                  <td class="mono">{{ String(u.id).padStart(6, "0") }}</td>
                  <td>{{ u.nickname }}</td>
                  <td>
                    <span class="tag">{{ u.identityName }}</span>
                  </td>
                  <td class="mono">{{ u.phone || "尚未填写" }}</td>
                </tr>
                <tr v-if="!users.length">
                  <td colspan="4" class="empty-cell">
                    {{ busy ? "正在读取…" : "还没有用户登录" }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <section v-else-if="section === 'audit'" class="table-panel">
          <AdminForm
            class="filter-bar"
            @submit="
              page = 1;
              load();
            "
          >
            <label
              >业务对象<AdminSelect v-model="filterType">
                <option value="">全部对象</option>
                <option value="user">用户</option>
                <option value="staff">工作人员</option>
                <option value="permission">功能权限</option>
              </AdminSelect></label
            ><label
              >对象编号<input
                v-model="filterId"
                maxlength="40"
                placeholder="如：1" /></label
            ><button class="secondary" :disabled="busy">查找记录</button>
          </AdminForm>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>操作时间</th>
                  <th>操作者</th>
                  <th>操作 / 对象</th>
                  <th>结果与原因</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="event in events" :key="event.id">
                  <td class="mono">
                    {{ new Date(event.create_time).toLocaleString("zh-CN") }}
                  </td>
                  <td>
                    {{
                      event.actor_kind === "staff"
                        ? "工作人员"
                        : event.actor_kind === "user"
                          ? "用户"
                          : "系统"
                    }}
                    #{{ event.actor_id }}
                  </td>
                  <td>
                    <strong>{{
                      actionNames[event.action] || event.action
                    }}</strong
                    ><small class="block muted"
                      >{{ event.object_type }} · {{ event.object_id }}</small
                    >
                  </td>
                  <td>
                    <span
                      :class="[
                        'tag',
                        event.result === 'success' ? '' : 'warning',
                      ]"
                      >{{
                        event.result === "success"
                          ? "成功"
                          : event.result === "denied"
                            ? "已拦截"
                            : "失败"
                      }}</span
                    >
                    <p class="audit-reason">{{ event.reason }}</p>
                    <small class="mono muted">{{ event.request_id }}</small>
                  </td>
                </tr>
                <tr v-if="!events.length">
                  <td colspan="4" class="empty-cell">
                    {{ busy ? "正在读取…" : "没有符合条件的记录" }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <div
          v-if="section === 'users' || section === 'audit'"
          class="pagination"
        >
          <span>共 {{ total }} 条 · 第 {{ page }} 页</span
          ><button
            class="secondary"
            :disabled="page === 1 || busy"
            @click="turnPage(-1)"
          >
            上一页</button
          ><button
            class="secondary"
            :disabled="page * 30 >= total || busy"
            @click="turnPage(1)"
          >
            下一页
          </button>
        </div>
        <OrderOperations
          v-if="section === 'shipping'"
          :key="'shipping:' + refreshKey"
          section="shipping"
        />
        <PaymentOperations
          v-if="section === 'orders'"
          :key="'orders:' + refreshKey"
        />
        <AfterSalesOperations
          v-if="section === 'aftersales'"
          :key="'aftersales:' + refreshKey"
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
        <OperationsExports
          v-if="['membership', 'orders', 'aftersales'].includes(section)"
          :permissions="me.permissions"
        />
        <FinanceExports
          v-if="
            [
              'wallet',
              'cashrefunds',
              'orders',
              'aftersales',
              'rewards',
            ].includes(section)
          "
          :permissions="me.permissions"
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
      </main>
      <footer class="admin-footer">
        清欣简妍肌 <span>·</span> 品牌方管理后台
      </footer>
    </div>
    <AdminDialog
      v-if="modal"
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
        <template v-if="modal !== 'remove'"
          ><label v-if="modal === 'create'"
            >登录账号<input
              v-model="form.username"
              required
              pattern="[a-zA-Z][a-zA-Z0-9_\-]{2,31}"
              data-error-message="账号须以字母开头，使用 3～32 位字母、数字、下划线或短横线"
              maxlength="32"
              autocomplete="off"
              placeholder="3～32 位字母、数字、下划线" /></label
          ><label
            >工作人员姓名<input
              v-model="form.displayName"
              required
              maxlength="30" /></label
          ><label v-if="modal === 'create'"
            >初始密码<input
              v-model="form.password"
              type="password"
              required
              minlength="12"
              maxlength="128"
              autocomplete="new-password"
              placeholder="至少 12 位"
          /></label>
          <fieldset>
            <legend>功能权限</legend>
            <label
              v-for="(label, key) in permissionNames"
              :key="key"
              class="check-label"
              ><input
                v-model="form.permissions"
                type="checkbox"
                :value="key"
              />{{ label }}</label
            >
          </fieldset>
          <label v-if="modal === 'edit'" class="check-label"
            ><input v-model="form.enabled" type="checkbox" />启用该账号</label
          ></template
        >
        <p v-else class="field-help">
          移除「{{
            selected?.displayName
          }}」后，该账号将无法登录，历史操作记录会保留。
        </p>
        <label
          >操作原因<textarea
            v-model="form.reason"
            required
            maxlength="200"
            rows="3"
            placeholder="说明本次新增、授权、停用或移除的原因"
          ></textarea>
        </label>
        <p v-if="error" class="message error" role="alert">{{ error }}</p>
        <div class="dialog-actions">
          <button
            type="button"
            class="secondary"
            :disabled="busy"
            @click="modal = null"
          >
            取消</button
          ><button
            :class="modal === 'remove' ? 'danger' : 'primary'"
            :disabled="busy"
          >
            {{
              busy ? "正在保存…" : modal === "remove" ? "确认移除" : "保存设置"
            }}
          </button>
        </div>
      </AdminForm>
    </AdminDialog>
  </div>
</template>
