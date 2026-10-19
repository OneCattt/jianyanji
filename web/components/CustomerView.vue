<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api, ApiError, type User } from "../client";
import ModalDialog from "./ModalDialog.vue";
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
      <header class="mobile-header">
        <span class="brand-seal">清</span><strong>清欣简妍肌</strong
        ><span class="caps">SKIN & CARE</span>
      </header>
      <div class="mobile-content">
        <template v-if="tab === 'home'">
          <section class="brand-hero">
            <span class="eyebrow">清欣 · 日常调肤</span>
            <h1>关照肌肤，<br />也关照自己。</h1>
            <p>认识清欣，从一份属于你的资料开始。</p>
            <div class="botanical" aria-hidden="true">
              <i></i><i></i><i></i><i></i>
            </div>
            <span class="hero-caption">清 / 欣 / 简 / 妍 / 肌</span>
          </section>
          <section class="welcome-block">
            <span class="eyebrow">欢迎来到清欣</span>
            <h2>你的调肤之旅，从这里开始</h2>
            <p>登录后查看当前身份，管理个人资料与联系方式。</p>
            <button
              class="primary full"
              @click="user ? (tab = 'mine') : login()"
              :disabled="busy"
            >
              {{
                user ? "前往我的清欣" : busy ? "正在登录…" : "登录，认识清欣"
              }}
              <span>↗</span>
            </button>
          </section>
        </template>
        <section v-else-if="tab === 'mall'" class="empty-state">
          <span class="empty-symbol">叶</span>
          <h1>好产品，正在准备中</h1>
          <p>商品上架后，会在这里与你见面。</p>
          <button class="secondary" @click="tab = 'home'">返回首页</button>
        </section>
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
        <details v-if="devAuth" class="dev-panel">
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
      <nav class="bottom-nav" aria-label="小程序导航">
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
