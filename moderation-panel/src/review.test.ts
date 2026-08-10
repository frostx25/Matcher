import { describe, expect, it } from "vitest";
import { decisionLabel, formatSubmittedAt } from "./review";

describe("review presentation", () => {
  it("formats recent submissions without exposing technical data", () => {
    expect(formatSubmittedAt("2026-08-05T12:29:30Z", Date.parse("2026-08-05T12:30:00Z")))
      .toBe("Enviada agora");
  });

  it("uses explicit labels for irreversible decisions", () => {
    expect(decisionLabel("approved")).toBe("Aprovar foto");
    expect(decisionLabel("blocked_adult")).toContain("conteúdo adulto");
    expect(decisionLabel("blocked_abusive")).toContain("conteúdo abusivo");
  });
});
