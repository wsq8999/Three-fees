import { calculateAdjustedThreshold } from "@/mocks/scenario-store";

describe("audit comparison evidence", () => {
  it("uses C × max(1, A/B) × 1.20 instead of a direct ratio", () => {
    const result = calculateAdjustedThreshold({
      currentTotal: 12_850.36,
      currentDays: 30,
      currentBenchmarkTotal: 12_000,
      referenceTotal: 8_400,
      referenceDays: 30,
      referenceBenchmarkTotal: 10_000,
    });

    expect(result.applicable).toBe(true);
    expect(result.k).toBeCloseTo(1.2);
    expect(result.threshold).toBeCloseTo(403.2);
    expect(result.overLimit).toBe(true);
  });

  it("marks missing inputs and a non-positive B as not applicable", () => {
    expect(
      calculateAdjustedThreshold({
        currentTotal: 100,
        currentDays: 30,
        currentBenchmarkTotal: 100,
        referenceTotal: 100,
        referenceDays: 30,
        referenceBenchmarkTotal: 0,
      }).applicable,
    ).toBe(false);
  });
});
