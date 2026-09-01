import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  XP_RULESET_V1,
  totalXpRequiredForLevel,
  calculateLevelFromTotalXp,
  evaluateXpDecision,
  reconstructServerSafetyScore,
} from '../../src/services/xpEngineService.js';

describe('XP Engine Progression & Level Threshold Boundaries', () => {
  test('Level threshold formula totalXpRequiredForLevel = round(100 * (level-1)^1.5)', () => {
    assert.equal(totalXpRequiredForLevel(1), 0);
    assert.equal(totalXpRequiredForLevel(2), 100);
    assert.equal(totalXpRequiredForLevel(3), 283);
    assert.equal(totalXpRequiredForLevel(4), 520);
    assert.equal(totalXpRequiredForLevel(5), 800);
    assert.equal(totalXpRequiredForLevel(6), 1118);
    assert.equal(totalXpRequiredForLevel(7), 1470);
    assert.equal(totalXpRequiredForLevel(8), 1852);
    assert.equal(totalXpRequiredForLevel(9), 2263);
    assert.equal(totalXpRequiredForLevel(10), 2700);
  });

  test('calculateLevelFromTotalXp calculates exact level boundaries and remaining XP', () => {
    // 0 XP -> Level 1
    const l0 = calculateLevelFromTotalXp(0);
    assert.equal(l0.currentLevel, 1);
    assert.equal(l0.progressPercent, 0.0);
    assert.equal(l0.xpRemaining, 100);

    // 99 XP -> Level 1
    const l99 = calculateLevelFromTotalXp(99);
    assert.equal(l99.currentLevel, 1);
    assert.equal(l99.xpEarnedInCurrentLevel, 99);
    assert.equal(l99.xpRemaining, 1);
    assert.equal(l99.progressPercent, 0.99);

    // 100 XP -> Level 2
    const l100 = calculateLevelFromTotalXp(100);
    assert.equal(l100.currentLevel, 2);
    assert.equal(l100.currentLevelStartingXp, 100);
    assert.equal(l100.nextLevelRequiredXp, 283);

    // 282 XP -> Level 2
    const l282 = calculateLevelFromTotalXp(282);
    assert.equal(l282.currentLevel, 2);

    // 283 XP -> Level 3
    const l283 = calculateLevelFromTotalXp(283);
    assert.equal(l283.currentLevel, 3);

    // 519 XP -> Level 3
    const l519 = calculateLevelFromTotalXp(519);
    assert.equal(l519.currentLevel, 3);

    // 520 XP -> Level 4
    const l520 = calculateLevelFromTotalXp(520);
    assert.equal(l520.currentLevel, 4);

    // 799 XP -> Level 4
    const l799 = calculateLevelFromTotalXp(799);
    assert.equal(l799.currentLevel, 4);

    // 800 XP -> Level 5
    const l800 = calculateLevelFromTotalXp(800);
    assert.equal(l800.currentLevel, 5);

    // 1117 XP -> Level 5
    const l1117 = calculateLevelFromTotalXp(1117);
    assert.equal(l1117.currentLevel, 5);

    // 1118 XP -> Level 6
    const l1118 = calculateLevelFromTotalXp(1118);
    assert.equal(l1118.currentLevel, 6);

    // 1469 XP -> Level 6
    const l1469 = calculateLevelFromTotalXp(1469);
    assert.equal(l1469.currentLevel, 6);

    // 1470 XP -> Level 7
    const l1470 = calculateLevelFromTotalXp(1470);
    assert.equal(l1470.currentLevel, 7);

    // 1851 XP -> Level 7
    const l1851 = calculateLevelFromTotalXp(1851);
    assert.equal(l1851.currentLevel, 7);

    // 1852 XP -> Level 8
    const l1852 = calculateLevelFromTotalXp(1852);
    assert.equal(l1852.currentLevel, 8);

    // 2262 XP -> Level 8
    const l2262 = calculateLevelFromTotalXp(2262);
    assert.equal(l2262.currentLevel, 8);
    assert.equal(l2262.currentLevelStartingXp, 1852);
    assert.equal(l2262.nextLevelRequiredXp, 2263);
    assert.equal(l2262.xpRemaining, 1);

    // 2263 XP -> Level 9
    const l2263 = calculateLevelFromTotalXp(2263);
    assert.equal(l2263.currentLevel, 9);
    assert.equal(l2263.currentLevelStartingXp, 2263);
    assert.equal(l2263.nextLevelRequiredXp, 2700);

    // 2699 XP -> Level 9
    const l2699 = calculateLevelFromTotalXp(2699);
    assert.equal(l2699.currentLevel, 9);
    assert.equal(l2699.xpRemaining, 1);

    // 2700 XP -> Level 10
    const l2700 = calculateLevelFromTotalXp(2700);
    assert.equal(l2700.currentLevel, 10);
    assert.equal(l2700.currentLevelStartingXp, 2700);
    assert.equal(l2700.nextLevelRequiredXp, 3162);
  });
});

describe('XP_RULESET_V1 Shared Golden Test Vectors', () => {
  const mockMetrics = { gps_quality: 1.0 };
  const createTrip = (score: number, durationMs: number, distanceM: number, quality: number) => ({
    id: '11111111-1111-1111-1111-111111111111',
    distance_m: distanceM,
    duration_ms: durationMs,
    telemetry_quality: quality,
    trip_classification: 'ELIGIBLE',
    mobility_mode: 'ROAD_VEHICLE',
    mobility_confidence: 90,
    safety_score: score,
  });

  test('Golden Vector 1: score 50, 20 mins, confidence 1.00 -> 8 XP', () => {
    const trip = createTrip(50, 1200000, 5000, 1.0);
    const decision = evaluateXpDecision(trip, { gps_quality: 1.0 }, [], 0, 0, 0);
    assert.equal(decision.eligible, true);
    assert.equal(decision.serverSafetyScore, 50);
    assert.equal(decision.confirmedXp, 8); // (8 + 0) * 1.0 * 1.0 = 8
    assert.equal(decision.rewardPoints, 4); // floor(8 / 2) = 4
  });

  test('Golden Vector 2: score 92, 20 mins, confidence 0.95 -> 27 XP', () => {
    const trip = createTrip(92, 1200000, 5000, 0.95);
    const decision = evaluateXpDecision(trip, { gps_quality: 0.95 }, [], 0, 0, 0);
    assert.equal(decision.eligible, true);
    assert.equal(decision.serverSafetyScore, 92);
    assert.equal(decision.confirmedXp, 27); // round((8 + 20.533) * 1.0 * 0.95) = round(27.106) = 27
    assert.equal(decision.rewardPoints, 13); // floor(27 / 2) = 13
  });

  test('Golden Vector 3: score 95, 20 mins, confidence 1.00 -> 30 XP', () => {
    const trip = createTrip(95, 1200000, 5000, 1.0);
    const decision = evaluateXpDecision(trip, { gps_quality: 1.0 }, [], 0, 0, 0);
    assert.equal(decision.eligible, true);
    assert.equal(decision.serverSafetyScore, 95);
    assert.equal(decision.confirmedXp, 30); // round((8 + 22) * 1.0 * 1.0) = 30
    assert.equal(decision.rewardPoints, 15);
  });

  test('Golden Vector 4: minimum exposure (2 mins, 120,000 ms) -> exposure factor 0.75', () => {
    const trip = createTrip(100, 120000, 1000, 1.0);
    const decision = evaluateXpDecision(trip, { gps_quality: 1.0 }, [], 0, 0, 0);
    assert.equal(decision.eligible, true);
    assert.equal(decision.confirmedXp, 23); // round((8 + 22) * 0.75 * 1.0) = 22.5 -> 23
    assert.equal(decision.rewardPoints, 11);
  });

  test('Golden Vector 5: maximum exposure (60 mins) -> exposure factor 1.25', () => {
    const trip = createTrip(100, 3600000, 20000, 1.0);
    const decision = evaluateXpDecision(trip, { gps_quality: 1.0 }, [], 0, 0, 0);
    assert.equal(decision.eligible, true);
    assert.equal(decision.confirmedXp, 38); // round((8 + 22) * 1.25 * 1.0) = 37.5 -> 38
    assert.equal(decision.rewardPoints, 19);
  });

  test('Golden Vector 6: minimum telemetry confidence (0.35) -> quality factor 0.70', () => {
    // completionBase + safetyBonus = 8 + 22 = 30
    // qualityFactor = clamp(0.35, 0.70, 1.00) = 0.70
    // round(30 * 1.00 * 0.70) = 21 XP
    const trip = createTrip(100, 1200000, 5000, 0.35);
    const decision = evaluateXpDecision(trip, { gps_quality: 1.0 }, [], 0, 0, 0);
    assert.equal(decision.eligible, true);
    assert.equal(decision.serverSafetyScore, 96);
    assert.equal(decision.confirmedXp, 21); // round(30 * 0.70) = 21
  });

  test('Golden Vector 7: XP lower clamp enforced at 5 XP', () => {
    // Uncapped raw: (8 + 0) * 0.75 * 0.70 = 4.2 -> 4. Clamped to min 5 XP.
    const trip = createTrip(50, 120000, 600, 0.35);
    const decision = evaluateXpDecision(trip, { gps_quality: 0.35 }, [], 0, 0, 0);
    assert.equal(decision.eligible, true);
    assert.equal(decision.confirmedXp, 5); // Clamped to lower bound 5
  });

  test('Golden Vector 8: XP upper clamp enforced at 38 XP', () => {
    const trip = createTrip(100, 3600000, 25000, 1.0);
    const decision = evaluateXpDecision(trip, { gps_quality: 1.0 }, [], 0, 0, 0);
    assert.equal(decision.eligible, true);
    assert.equal(decision.confirmedXp, 38); // Clamped to upper bound 38
  });

  test('Golden Vector 9: Ineligible trip returns exactly 0 XP', () => {
    const shortTrip = createTrip(100, 60000, 200, 1.0);
    const decision = evaluateXpDecision(shortTrip, { gps_quality: 1.0 }, [], 0, 0, 0);
    assert.equal(decision.eligible, false);
    assert.equal(decision.status, 'INELIGIBLE');
    assert.equal(decision.confirmedXp, 0);
    assert.equal(decision.rewardPoints, 0);
  });
});

describe('XP Caps Semantics & UTC Boundaries', () => {
  const baseTrip = {
    id: '11111111-1111-1111-1111-111111111111',
    distance_m: 5000,
    duration_ms: 1200000,
    telemetry_quality: 1.0,
    trip_classification: 'ELIGIBLE',
    mobility_mode: 'ROAD_VEHICLE',
    mobility_confidence: 90,
    safety_score: 100,
  };
  const mockMetrics = { gps_quality: 1.0 };

  test('Remaining daily cap values: 0, 1, 3, 5, and full cap', () => {
    const dec0 = evaluateXpDecision(baseTrip, mockMetrics, [], 100, 1, 0);
    assert.equal(dec0.confirmedXp, 0);
    assert.ok(dec0.reasonCodes.includes('DAILY_CAP_APPLIED'));

    const dec1 = evaluateXpDecision(baseTrip, mockMetrics, [], 99, 1, 0);
    assert.equal(dec1.confirmedXp, 1);

    const dec3 = evaluateXpDecision(baseTrip, mockMetrics, [], 97, 1, 0);
    assert.equal(dec3.confirmedXp, 3);

    const dec5 = evaluateXpDecision(baseTrip, mockMetrics, [], 95, 1, 0);
    assert.equal(dec5.confirmedXp, 5);

    const decFull = evaluateXpDecision(baseTrip, mockMetrics, [], 0, 0, 0);
    assert.equal(decFull.confirmedXp, 30);
  });

  test('Max daily completion awards cap (5 awards/day)', () => {
    const decAwardsCap = evaluateXpDecision(baseTrip, mockMetrics, [], 50, 5, 0);
    assert.equal(decAwardsCap.confirmedXp, 0);
    assert.ok(decAwardsCap.reasonCodes.includes('DAILY_CAP_APPLIED'));
  });

  test('Weekly cap boundary (450 XP max)', () => {
    const decWeekly = evaluateXpDecision(baseTrip, mockMetrics, [], 0, 0, 440);
    assert.equal(decWeekly.confirmedXp, 10);
    assert.ok(decWeekly.reasonCodes.includes('WEEKLY_CAP_APPLIED'));
  });
});
