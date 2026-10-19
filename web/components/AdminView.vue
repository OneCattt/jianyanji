<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import ModalDialog from "./ModalDialog.vue";
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
const sections = computed(() => [
  { key: "overview", label: "工作台", symbol: "◈" },
  ...(me.value?.permissions.includes("users:read")
    ? [{ key: "users", label: "用户资料", symbol: "◎" }]
    : []),
  ...(me.value?.permissions.includes("staff:manage")
    ? [{ key: "staff", label: "工作人员", symbol: "♧" }]
    : []),
  ...(me.value?.permissions.includes("audit:read")
    ? [{ key: "audit", label: "操作记录", symbol: "≡" }]
    : []),
]);
const sectionTitle = computed(
  () => sections.value.find((s) => s.key === section.value)?.label || "工作台",
);
function report(e: unknown) {
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
  page.value = 1;
  notice.value = "";
  error.value = "";
  await load();
}
function open(mode: "create" | "edit" | "remove", value?: Staff) {
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
    notice.value =
      modal.value === "remove"
        ? "工作人员已移除，历史操作记录已保留"
        : "工作人员设置已保存";
    modal.value = null;
    me.value = await api<Staff>("/admin/me");
    if (!me.value.permissions.includes("staff:manage"))
      section.value = "overview";
    else await load();
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
      <form class="login-form" @submit.prevent="login">
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
      </form>
    </section>
  </main>
  <div v-else class="admin-shell">
    <aside class="sidebar">
      <a href="/" class="brand-word">清欣<span>简妍肌</span></a
      ><span class="workspace-label">品牌方工作空间</span>
      <nav aria-label="后台功能">
        <button
          v-for="item in sections"
          :key="item.key"
          :aria-current="section === item.key ? 'page' : undefined"
          @click="navigate(item.key)"
        >
          <span>{{ item.symbol }}</span
          >{{ item.label
          }}<span v-if="section === item.key" class="nav-indicator">·</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <span class="staff-avatar">{{ me.displayName.slice(0, 1) }}</span>
        <div>
          <strong>{{ me.displayName }}</strong
          ><small>{{ me.username }}</small>
        </div>
        <button
          title="退出登录"
          aria-label="退出登录"
          @click="logout"
          :disabled="busy"
        >
          ↗
        </button>
      </div>
    </aside>
    <div class="admin-main">
      <header class="admin-topbar">
        <span>工作空间 <span class="muted">/</span> {{ sectionTitle }}</span
        ><a href="/">查看小程序体验 ↗</a>
      </header>
      <main class="admin-content">
        <div class="admin-heading">
          <div>
            <span class="eyebrow"
              >品牌运营 ·
              {{ section === "overview" ? "工作台" : "账号与身份" }}</span
            >
            <h1>
              {{
                section === "overview"
                  ? `你好，${me.displayName}`
                  : sectionTitle
              }}
            </h1>
            <p>
              {{
                section === "staff"
                  ? "为每位同事分配合适的权限，让协作有据可查。"
                  : section === "users"
                    ? "查看用户的有效身份与必要联系资料。"
                    : section === "audit"
                      ? "按业务对象查找操作，核对谁在何时做了什么。"
                      : "从这里开始今天的品牌工作。"
              }}
            </p>
          </div>
          <button
            v-if="section === 'staff'"
            class="primary"
            @click="open('create')"
          >
            ＋ 新增工作人员</button
          ><button
            v-else-if="section !== 'overview'"
            class="secondary"
            @click="load"
            :disabled="busy"
          >
            刷新记录</button
          ><span v-else class="date-label">{{
            new Date().toLocaleDateString("zh-CN", {
              year: "numeric",
              month: "long",
              day: "numeric",
            })
          }}</span>
        </div>
        <p v-if="error && !modal" class="message error" role="alert">
          {{ error }}
        </p>
        <p v-if="notice" class="message success" role="status">{{ notice }}</p>
        <template v-if="section === 'overview'"
          ><section class="overview-banner">
            <span class="eyebrow">当前工作身份</span>
            <h2>品牌方工作人员</h2>
            <p>
              后台功能按账号授权。购买时的身份与价格，<br />以小程序中的有效身份为准。
            </p>
            <span class="banner-stamp" aria-hidden="true">清<br />欣</span>
          </section>
          <div class="section-heading">
            <h2>可用工作入口</h2>
            <span class="muted">{{ sections.length - 1 }} 项已授权</span>
          </div>
          <div class="entry-grid">
            <button
              v-for="item in sections.filter((s) => s.key !== 'overview')"
              :key="item.key"
              @click="navigate(item.key)"
            >
              <span class="entry-icon">{{ item.symbol }}</span>
              <h3>{{ item.label }}</h3>
              <p>
                {{
                  item.key === "staff"
                    ? "管理账号、权限与启用状态"
                    : item.key === "users"
                      ? "核对用户资料与有效身份"
                      : "查看关键操作与处理结果"
                }}
              </p>
              <span class="entry-arrow">↗</span>
            </button>
            <p v-if="sections.length === 1" class="muted">
              当前尚未分配业务权限，请联系管理员。
            </p>
          </div>
          <section class="access-note">
            <span>◎</span>
            <div>
              <strong>每次协作，都留有记录</strong>
              <p>授权、停用和资料修改会保留操作者及原因，方便后续核对。</p>
            </div>
          </section></template
        >
        <section v-else-if="section === 'staff'" class="table-panel">
          <div class="panel-title">
            <h2>工作人员列表</h2>
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
            <h2>用户资料</h2>
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
        <section v-else class="table-panel">
          <form
            class="filter-bar"
            @submit.prevent="
              page = 1;
              load();
            "
          >
            <label
              >业务对象<select v-model="filterType">
                <option value="">全部对象</option>
                <option value="user">用户</option>
                <option value="staff">工作人员</option>
                <option value="permission">功能权限</option>
              </select></label
            ><label
              >对象编号<input
                v-model="filterId"
                maxlength="40"
                placeholder="如：1" /></label
            ><button class="secondary" :disabled="busy">查找记录</button>
          </form>
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
      </main>
      <footer class="admin-footer">
        清欣简妍肌 <span>·</span> 品牌方管理后台
      </footer>
    </div>
    <ModalDialog
      v-if="modal"
      labelledby="staff-form-title"
      @close="modal = null"
      ><section class="dialog">
        <header>
          <h2 id="staff-form-title">
            {{
              modal === "create"
                ? "新增工作人员"
                : modal === "remove"
                  ? "移除工作人员"
                  : "修改授权与状态"
            }}
          </h2>
          <button class="icon-button" aria-label="关闭" @click="modal = null">
            ×
          </button>
        </header>
        <form @submit.prevent="save">
          <template v-if="modal !== 'remove'"
            ><label v-if="modal === 'create'"
              >登录账号<input
                v-model="form.username"
                required
                pattern="[a-zA-Z][a-zA-Z0-9_-]{2,31}"
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
            <button type="button" class="secondary" @click="modal = null">
              取消</button
            ><button
              :class="modal === 'remove' ? 'danger' : 'primary'"
              :disabled="busy"
            >
              {{
                busy
                  ? "正在保存…"
                  : modal === "remove"
                    ? "确认移除"
                    : "保存设置"
              }}
            </button>
          </div>
        </form>
      </section></ModalDialog
    >
  </div>
</template>
