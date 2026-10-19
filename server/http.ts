import type { RequestHandler, Response } from "express";
import type { DB, Permission } from "./db.ts";
export class HttpError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
export interface BusinessContext {
  db: DB;
  userAuth: RequestHandler;
  staffAuth: RequestHandler;
  optionalUser: RequestHandler;
  permit: (permission: Permission) => RequestHandler;
  audit: (
    res: Response,
    action: string,
    objectType: string,
    objectId: string | number,
    reason: string,
    result?: "success" | "denied" | "failure",
  ) => void;
}
export const fail = (message: string, code = "BUSINESS_CONFLICT"): never => {
  throw new HttpError(409, code, message);
};
