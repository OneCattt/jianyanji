import {
  computed,
  onScopeDispose,
  ref,
  shallowRef,
  watch,
  type Ref,
} from "vue";
import { api } from "./client";

export interface ListPage<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

/** 后台列表统一使用服务端分页；筛选变化回到首页，旧请求不能覆盖新结果。 */
export function useAdminList<T>(
  path: string | (() => string),
  filters: () => Record<string, string | number | undefined>,
  error: Ref<string>,
  enabled: () => boolean = () => true,
) {
  const rows = shallowRef<T[]>([]);
  const total = ref(0);
  const current = ref(1);
  const pageSize = ref(20);
  const loading = ref(false);
  let generation = 0;
  let controller: AbortController | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;

  async function load(): Promise<T[]> {
    clearTimeout(timer);
    if (!enabled()) return [];
    const request = ++generation;
    controller?.abort();
    controller = new AbortController();
    loading.value = true;
    const query = new URLSearchParams({
      page: String(current.value),
      pageSize: String(pageSize.value),
    });
    for (const [key, value] of Object.entries(filters()))
      if (value !== undefined && value !== "") query.set(key, String(value));
    try {
      const result = await api<ListPage<T>>(
        `${typeof path === "function" ? path() : path}?${query}`,
        {
          signal: controller.signal,
        },
      );
      if (request !== generation) return rows.value;
      total.value = result.total;
      // 筛选或业务处理后末页消失时，读取最后一个有效页。
      const last = Math.max(1, Math.ceil(result.total / pageSize.value));
      if (current.value > last) {
        current.value = last;
        return await load();
      }
      rows.value = result.items;
      return result.items;
    } catch (e) {
      if (request !== generation) return rows.value;
      throw e;
    } finally {
      if (request === generation) loading.value = false;
    }
  }

  async function refresh() {
    error.value = "";
    try {
      await load();
    } catch (e) {
      error.value = e instanceof Error ? e.message : "列表读取失败，请重试";
    }
  }
  const pagination = computed(() => ({
    current: current.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: ["10", "20", "50"],
    hideOnSinglePage: false,
    showTotal: (count: number) => `共 ${count} 条`,
    onChange: (page: number, size: number) => {
      current.value = size === pageSize.value ? page : 1;
      pageSize.value = size;
      rows.value = [];
      void refresh();
    },
  }));
  watch(
    () => JSON.stringify(filters()),
    () => {
      controller?.abort();
      generation++;
      current.value = 1;
      rows.value = [];
      total.value = 0;
      clearTimeout(timer);
      if (enabled()) timer = setTimeout(() => void refresh(), 250);
      else loading.value = false;
    },
  );
  onScopeDispose(() => {
    generation++;
    clearTimeout(timer);
    controller?.abort();
  });
  return { rows, total, loading, pagination, load };
}
