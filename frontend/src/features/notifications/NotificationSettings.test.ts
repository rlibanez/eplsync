import { describe, expect, it } from "vitest";
import {
  allowsNotification,
  notificationDefaults,
  parseNotificationPreferences,
} from "./NotificationSettings";
describe("notification preferences", () => {
  it("defaults to starts and all results", () => {
    expect(allowsNotification(notificationDefaults, "info", "start")).toBe(
      true,
    );
    for (const tone of ["success", "warning", "error", "info"] as const)
      expect(allowsNotification(notificationDefaults, tone)).toBe(true);
  });
  it("selects starts independently and applies result severity", () => {
    const starts = { ...notificationDefaults, starts: true, finishes: false };
    expect(allowsNotification(starts, "info", "start")).toBe(true);
    expect(allowsNotification(starts, "error")).toBe(false);
    const errors = { ...notificationDefaults, success: false, warning: false };
    expect(allowsNotification(errors, "error")).toBe(true);
    expect(allowsNotification(errors, "warning")).toBe(false);
    expect(allowsNotification(errors, "success")).toBe(false);
  });
  it("validates saved preferences and bounds duration", () => {
    expect(parseNotificationPreferences("null")).toEqual(notificationDefaults);
    expect(parseNotificationPreferences("broken")).toEqual(
      notificationDefaults,
    );
    expect(
      parseNotificationPreferences('{"starts":"false","seconds":1000}'),
    ).toEqual({ ...notificationDefaults, seconds: 60 });
    expect(parseNotificationPreferences('{"seconds":-1}').seconds).toBe(1);
  });
});
