import { describe, expect, it } from "vitest";
import { appointment } from "@/test/fixtures";
import { todayCounts } from "./todayCounts";

describe("todayCounts", () => {
  it("groups every status the way the front desk thinks of the day", () => {
    const list = [
      appointment({ id: "1", status: "BOOKED" }),
      appointment({ id: "2", status: "CONFIRMED" }),
      appointment({ id: "3", status: "ARRIVED" }),
      appointment({ id: "4", status: "WAITING" }),
      appointment({ id: "5", status: "SKIPPED" }),
      appointment({ id: "6", status: "IN_CONSULTATION" }),
      appointment({ id: "7", status: "COMPLETED" }),
      appointment({ id: "8", status: "CANCELLED" }),
      appointment({ id: "9", status: "NO_SHOW" }),
    ];
    expect(todayCounts(list, () => undefined)).toEqual({
      total: 9, expected: 2, here: 3, withDoctor: 1, completed: 1, missed: 2,
    });
  });

  it("uses the live queue status when it is newer than the list", () => {
    const list = [appointment({ id: "a", status: "WAITING" })];
    expect(todayCounts(list, (id) => (id === "a" ? "COMPLETED" : undefined))).toMatchObject({ here: 0, completed: 1 });
  });
});
