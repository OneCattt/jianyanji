import {
  inject,
  onScopeDispose,
  provide,
  reactive,
  watch,
  type InjectionKey,
} from "vue";
import { api } from "./client";

interface UserName {
  id: number;
  nickname: string;
}
interface Entry {
  name?: string;
  failed?: boolean;
}

/** 一个管理员会话内合并读取和复用昵称；每批最多100位，不逐行查询。 */
export function createAdminUserNames(
  read = (ids: string[], signal: AbortSignal) =>
    api<{ items: UserName[] }>(`/admin/user-labels?ids=${ids.join(",")}`, {
      signal,
    }),
) {
  const entries = reactive(new Map<string, Entry>());
  const requested = new Set<string>(),
    pending = new Set<string>();
  const controllers = new Set<AbortController>();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let generation = 0;
  function enqueue(id: string) {
    if (requested.has(id)) return;
    requested.add(id);
    pending.add(id);
    timer ??= setTimeout(flush, 0);
  }
  async function flush() {
    timer = undefined;
    const ids = [...pending].slice(0, 100);
    ids.forEach((id) => pending.delete(id));
    if (pending.size) timer = setTimeout(flush, 0);
    const current = generation,
      controller = new AbortController();
    controllers.add(controller);
    try {
      const result = await read(ids, controller.signal);
      if (current !== generation) return;
      const names = new Map(
        result.items.map((row) => [String(row.id), row.nickname]),
      );
      ids.forEach((id) => entries.set(id, { name: names.get(id) }));
    } catch {
      if (current === generation)
        ids.forEach((id) => entries.set(id, { failed: true }));
    } finally {
      controllers.delete(controller);
    }
  }
  function key(value: unknown) {
    const id = String(value ?? "");
    return /^[1-9]\d*$/.test(id) && Number.isSafeInteger(Number(id)) ? id : "";
  }
  function label(value: unknown, knownName?: string) {
    const id = key(value);
    if (!id) return "—";
    if (knownName?.trim()) return `${knownName}（#${id}）`;
    const entry = entries.get(id);
    if (!entry) {
      enqueue(id);
      return `用户 #${id}（读取中）`;
    }
    if (entry.failed) return `用户 #${id}（名称读取失败）`;
    return `${entry.name === undefined ? "资料不可用" : entry.name.trim() || "未填写昵称"}（#${id}）`;
  }
  function retry(value: unknown) {
    const id = key(value);
    if (!id) return;
    entries.delete(id);
    requested.delete(id);
    enqueue(id);
  }
  function reset() {
    generation++;
    clearTimeout(timer);
    timer = undefined;
    controllers.forEach((controller) => controller.abort());
    controllers.clear();
    pending.clear();
    requested.clear();
    entries.clear();
  }
  return {
    label,
    retry,
    failed: (id: unknown) => Boolean(entries.get(key(id))?.failed),
    reset,
  };
}
const namesKey: InjectionKey<ReturnType<typeof createAdminUserNames>> =
  Symbol("admin-user-names");
export function provideAdminUserNames(session: () => number | undefined) {
  const names = createAdminUserNames();
  provide(namesKey, names);
  watch(session, names.reset, { flush: "sync" });
  onScopeDispose(names.reset);
  return names;
}
export function useAdminUserNames() {
  const names = inject(namesKey);
  if (!names) throw new Error("用户名称展示须位于后台会话内");
  return names;
}
