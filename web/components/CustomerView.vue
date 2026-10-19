<script setup lang="ts">
import { ref, onMounted, watch } from "vue";
import { api, ApiError, type User } from "../client";
import CommerceView from "./CommerceView.vue";
import CustomerOrders from "./CustomerOrders.vue";
import Storefront from "./Storefront.vue";
import ModalDialog from "./ModalDialog.vue";
const commerceOpen = ref(false),
  commerceItems = ref<{ skuId: number; quantity: number }[] | undefined>(),
  ordersOpen = ref(false),
  orderId = ref<number | undefined>();
function checkout(items?: { skuId: number; quantity: number }[]) {
  if (!token.value) {
    tab.value = "mine";
    notice.value = "请先登录再结算";
    return;
  }
  commerceItems.value = items;
  commerceOpen.value = true;
  ordersOpen.value = false;
}
function showOrder(id?: number) {
  commerceOpen.value = false;
  ordersOpen.value = true;
  orderId.value = id;
}
const user = ref<User | null>(null),
  token = ref(sessionStorage.getItem("jyj_user") || "");
const devAuth = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  tab = ref("home"),
  editing = ref(false);
const nickname = ref(""),
  phone = ref(""),
  account = ref("visitor");
watch(tab, () => {
  commerceOpen.value = false;
  ordersOpen.value = false;
});
onMounted(async () => {
  try {
    const context = await api<{ devAuth: boolean }>("/public/context");
    devAuth.value = context.devAuth;
    if (token.value) {
      user.value = await api<User>("/me", { token: token.value });
      tab.value = "mine";
    }
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      token.value = "";
      sessionStorage.removeItem("jyj_user");
    } else error.value = (e as Error).message;
  }
});
async function login() {
  if (!devAuth.value) {
    error.value = "请在微信小程序中使用微信登录";
    return;
  }
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const result = await api<{ token: string; user: User }>("/auth/dev", {
      method: "POST",
      body: { account: account.value },
    });
    token.value = result.token;
    sessionStorage.setItem("jyj_user", result.token);
    user.value = result.user;
    tab.value = "mine";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
function edit() {
  if (!user.value) return;
  nickname.value = user.value.nickname;
  phone.value = user.value.phone;
  editing.value = true;
  notice.value = "";
}
async function save() {
  busy.value = true;
  error.value = "";
  try {
    user.value = await api<User>("/me", {
      method: "PATCH",
      token: token.value,
      body: { nickname: nickname.value, phone: phone.value },
    });
    editing.value = false;
    notice.value = "个人资料已保存";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function logout() {
  busy.value = true;
  error.value = "";
  try {
    await api("/auth/logout", { method: "POST", token: token.value });
    user.value = null;
    token.value = "";
    sessionStorage.removeItem("jyj_user");
    editing.value = false;
    notice.value = "";
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      user.value = null;
      token.value = "";
      sessionStorage.removeItem("jyj_user");
    } else error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="customer-stage">
    <header class="preview-rail">
      <a href="/" class="brand-word">清欣<span>简妍肌</span></a
      ><span>由心开始，从容调肤</span><a href="/admin">品牌方后台 ↗</a>
    </header>
    <main class="phone-page">
      <header v-if="tab === 'mine'" class="mobile-header">
        <span class="brand-seal">清</span><strong>清欣简妍肌</strong
        ><span class="caps">SKIN & CARE</span>
      </header>
      <div class="mobile-content">
        <CommerceView
          v-if="commerceOpen"
          :token="token"
          :initialItems="commerceItems"
          @close="commerceOpen = false"
          @order="showOrder"
        />
        <CustomerOrders
          v-else-if="ordersOpen"
          :token="token"
          :orderId="orderId"
          @close="ordersOpen = false"
        />
        <Storefront
          v-else-if="tab !== 'mine'"
          :tab="tab"
          :token="token"
          @navigate="tab = $event"
          @buy="checkout"
          @cart="checkout()"
        />
        <template v-else>
          <div class="page-heading">
            <span class="eyebrow">MY QINGXIN</span>
            <h1>我的清欣</h1>
            <p>每一份身份，都有清楚的记录。</p>
          </div>
          <section class="identity-card">
            <div class="identity-top">
              <span class="avatar">{{
                user?.nickname.slice(0, 1) || "清"
              }}</span>
              <div>
                <h2>{{ user?.nickname || "欢迎，新朋友" }}</h2>
                <span class="identity-tag">{{
                  user ? user.identityName : "尚未登录"
                }}</span>
              </div>
            </div>
            <p>
              {{
                user
                  ? user.identity === "D"
                    ? "你当前尚未入驻成为代理，登录不会自动开通代理身份。"
                    : "你的代理身份已生效，购买时将使用对应身份价格。"
                  : "登录后查看当前身份，保存你的联系方式。"
              }}
            </p>
            <button
              v-if="!user"
              class="primary full"
              @click="login"
              :disabled="busy"
            >
              {{ busy ? "正在登录…" : "登录清欣" }}</button
            ><span v-else class="card-number"
              >清欣用户 · {{ String(user.id).padStart(6, "0") }}</span
            >
          </section>
          <section v-if="user" class="profile-section">
            <div class="section-heading">
              <button class="text-button" @click="showOrder()">
                我的订单 ›</button
              ><button class="text-button" @click="checkout()">购物袋 ›</button>
            </div>
            <div class="section-heading">
              <h2>个人资料</h2>
              <button class="text-button" @click="edit" :disabled="busy">
                编辑资料 ↗
              </button>
            </div>
            <dl>
              <div>
                <dt>昵称</dt>
                <dd>{{ user.nickname }}</dd>
              </div>
              <div>
                <dt>联系电话</dt>
                <dd>{{ user.phone || "尚未填写" }}</dd>
              </div>
              <div>
                <dt>当前身份</dt>
                <dd>{{ user.identityName }}</dd>
              </div>
            </dl>
            <p class="field-help">
              联系电话用于品牌方与你沟通，不代表已完成手机号认证。
            </p>
            <button class="logout-button" @click="logout" :disabled="busy">
              退出登录
            </button>
          </section>
        </template>
        <p v-if="error" role="alert" class="message error">{{ error }}</p>
        <p v-if="notice" role="status" class="message success">{{ notice }}</p>
        <details
          v-if="devAuth && tab === 'mine' && !commerceOpen && !ordersOpen"
          class="dev-panel"
        >
          <summary>本地调试账号</summary>
          <label
            >选择已有身份<select v-model="account">
              <option value="visitor">游客</option>
              <option value="store">调肤店主（需初始化演示数据）</option>
              <option value="principal">调肤主理人（需初始化演示数据）</option>
            </select></label
          >
          <p>调试入口只在本地显式开启；正式登录请使用微信小程序。</p>
        </details>
      </div>
      <nav
        v-if="!commerceOpen && !ordersOpen"
        class="bottom-nav"
        aria-label="小程序导航"
      >
        <button
          :aria-current="tab === 'home' ? 'page' : undefined"
          @click="tab = 'home'"
        >
          <span>⌂</span>首页</button
        ><button
          :aria-current="tab === 'mall' ? 'page' : undefined"
          @click="tab = 'mall'"
        >
          <span>♧</span>商城</button
        ><button
          :aria-current="tab === 'mine' ? 'page' : undefined"
          @click="tab = 'mine'"
        >
          <span>◎</span>我的
        </button>
      </nav>
    </main>
    <p class="preview-note">清欣简妍肌 · 浏览器体验入口</p>
    <ModalDialog
      v-if="editing"
      labelledby="profile-title"
      @close="editing = false"
      ><section class="dialog">
        <header>
          <h2 id="profile-title">编辑个人资料</h2>
          <button
            aria-label="关闭"
            class="icon-button"
            @click="editing = false"
          >
            ×
          </button>
        </header>
        <form @submit.prevent="save">
          <label
            >昵称<input
              v-model="nickname"
              required
              maxlength="30"
              autofocus /></label
          ><label
            >联系电话<input
              v-model="phone"
              type="tel"
              inputmode="tel"
              pattern="1[3-9][0-9]{9}|^$"
              maxlength="11"
              placeholder="可选，填写 11 位手机号"
          /></label>
          <p v-if="error" role="alert" class="message error">{{ error }}</p>
          <div class="dialog-actions">
            <button type="button" class="secondary" @click="editing = false">
              取消</button
            ><button class="primary" :disabled="busy">
              {{ busy ? "正在保存…" : "保存资料" }}
            </button>
          </div>
        </form>
      </section></ModalDialog
    >
  </div>
</template>
