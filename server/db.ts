import { migratePayments } from "./payments.ts";
import { migrateFulfillment } from "./fulfillment.ts";
import { migrateMembership } from "./membership.ts";
import { migrateWallet } from "./wallet.ts";
import { migrateRewards } from "./rewards.ts";
import { migrateCashRefunds } from "./cash-refunds.ts";
import { migrateAftersales } from "./aftersales.ts";
import { migrateOrders } from "./orders.ts";
import { migrateCatalog } from "./catalog.ts";
import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { scryptSync, randomBytes, timingSafeEqual } from "node:crypto";

export const permissions = [
  "staff:manage",
  "users:read",
  "audit:read",
  "content:manage",
  "catalog:manage",
  "orders:manage",
  "onboarding:manage",
  "finance:manage",
  "rewards:manage",
] as const;
export type Permission = (typeof permissions)[number];
export type Identity = "B" | "C" | "D";
export interface UserRow {
  id: number;
  openid: string;
  nickname: string;
  phone: string;
  identity: Identity;
  identity_valid_until: string | null;
  is_deleted: number;
}
export interface StaffRow {
  id: number;
  username: string;
  display_name: string;
  password_hash: string;
  permissions: string;
  enabled: number;
  is_deleted: number;
}

// 统一审计列用于所有业务表；停用和移除均保留原始记录。
export const auditColumns = `
  update_user_id INTEGER NOT NULL DEFAULT 0,
  create_user_id INTEGER NOT NULL DEFAULT 0,
  create_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  update_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  is_deleted INTEGER NOT NULL DEFAULT 0 CHECK (is_deleted IN (0,1))`;

export function openDatabase(path: string) {
  if (path !== ":memory:") mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec(
    `PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;`,
  );
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY, openid TEXT NOT NULL, nickname TEXT NOT NULL DEFAULT '微信用户',
      phone TEXT NOT NULL DEFAULT '', identity TEXT NOT NULL DEFAULT 'D' CHECK(identity IN ('B','C','D')),
      identity_valid_until TEXT, ${auditColumns}
    );
    CREATE UNIQUE INDEX IF NOT EXISTS users_openid_active ON users(openid) WHERE is_deleted=0;
    CREATE TABLE IF NOT EXISTS staff (
      id INTEGER PRIMARY KEY, username TEXT NOT NULL, display_name TEXT NOT NULL,
      password_hash TEXT NOT NULL, permissions TEXT NOT NULL DEFAULT '[]',
      enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)), ${auditColumns}
    );
    CREATE UNIQUE INDEX IF NOT EXISTS staff_username_active ON staff(username) WHERE is_deleted=0;
    CREATE TABLE IF NOT EXISTS sessions (
      id INTEGER PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, kind TEXT NOT NULL CHECK(kind IN ('user','staff')),
      subject_id INTEGER NOT NULL, expires_at TEXT NOT NULL, ${auditColumns}
    );
    CREATE TABLE IF NOT EXISTS audit_events (
      id INTEGER PRIMARY KEY, actor_kind TEXT NOT NULL, actor_id INTEGER NOT NULL,
      action TEXT NOT NULL, object_type TEXT NOT NULL, object_id TEXT NOT NULL,
      result TEXT NOT NULL, reason TEXT NOT NULL, request_id TEXT NOT NULL, ${auditColumns}
    );
    CREATE INDEX IF NOT EXISTS audit_object ON audit_events(object_type,object_id,id);
  `);
  migrateCatalog(db);
  migrateOrders(db);
  migrateAftersales(db);
  migratePayments(db);
  migrateFulfillment(db);
  migrateRewards(db);
  migrateMembership(db);
  migrateWallet(db);
  migrateCashRefunds(db);
  return db;
}
export type DB = ReturnType<typeof openDatabase>;

const activeTransactions = new WeakSet<DB>();
export function assertTransaction(db: DB, operation: string) {
  if (db.isTransaction !== true && !activeTransactions.has(db))
    throw new Error(operation + " 必须在调用方事务内运行");
}

export function transaction<T>(db: DB, work: () => T): T {
  db.exec("BEGIN IMMEDIATE");
  activeTransactions.add(db);
  try {
    const result = work();
    db.exec("COMMIT");
    return result;
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  } finally {
    activeTransactions.delete(db);
  }
}

export function passwordHash(password: string) {
  const salt = randomBytes(16).toString("hex");
  return `${salt}:${scryptSync(password, salt, 64).toString("hex")}`;
}
export function checkPassword(password: string, stored: string) {
  const [salt, hash] = stored.split(":");
  const actual = scryptSync(password, salt, 64);
  const expected = Buffer.from(hash, "hex");
  return expected.length === actual.length && timingSafeEqual(actual, expected);
}
export function effectiveIdentity(user: UserRow): Identity {
  return user.identity_valid_until &&
    Date.parse(user.identity_valid_until) <= Date.now()
    ? "D"
    : user.identity;
}
export function publicUser(user: UserRow) {
  const identity = effectiveIdentity(user);
  return {
    id: user.id,
    nickname: user.nickname,
    phone: user.phone,
    identity,
    identityName: { B: "调肤主理人", C: "调肤店主", D: "游客" }[identity],
  };
}
export function publicStaff(staff: StaffRow) {
  return {
    id: staff.id,
    username: staff.username,
    displayName: staff.display_name,
    permissions: JSON.parse(staff.permissions) as Permission[],
    enabled: Boolean(staff.enabled),
  };
}

export interface AuditInput {
  actorKind: "user" | "staff" | "system";
  actorId: number;
  action: string;
  objectType: string;
  objectId: string | number;
  result: "success" | "denied" | "failure";
  reason: string;
  requestId: string;
}
export function recordAudit(db: DB, event: AuditInput) {
  db.prepare(
    `INSERT INTO audit_events(actor_kind,actor_id,action,object_type,object_id,result,reason,request_id,create_user_id,update_user_id)
    VALUES(?,?,?,?,?,?,?,?,?,?)`,
  ).run(
    event.actorKind,
    event.actorId,
    event.action,
    event.objectType,
    String(event.objectId),
    event.result,
    event.reason,
    event.requestId,
    event.actorId,
    event.actorId,
  );
}

export function createStaff(
  db: DB,
  username: string,
  displayName: string,
  password: string,
  grants: readonly string[],
  actor = 0,
) {
  const result = db
    .prepare(
      `INSERT INTO staff(username,display_name,password_hash,permissions,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)`,
    )
    .run(
      username,
      displayName,
      passwordHash(password),
      JSON.stringify(grants),
      actor,
      actor,
    );
  return Number(result.lastInsertRowid);
}
