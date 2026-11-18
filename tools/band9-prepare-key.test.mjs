import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extractBand9Key } from './band9-prepare-key.mjs';

const first = '112233445566778899aabbccddeeff00';
const second = 'ffeeddccbbaa99887766554433221100';

test('extracts a unique model-associated candidate across repeated fields', () => {
  assert.equal(extractBand9Key(`Xiaomi Smart Band 9 authKey="${first}" token=${first}\nMi Smart Band 6 authKey=${second}`), first);
});

test('accepts JSON fields and normalizes uppercase', () => {
  assert.equal(extractBand9Key(`{"productName":"Xiaomi Smart Band 9","authKey":"${first.toUpperCase()}"}`), first);
});

test('rejects ambiguous, unrelated, invalid, and all-zero keys', () => {
  for (const text of [
    `Xiaomi Smart Band 9 authKey=${first} encryptKey=${second}`,
    `Xiaomi Smart Band 9 Active authKey=${first}`,
    `Xiaomi Smart Band 9 Pro authKey=${first}`,
    `Xiaomi Smart Band 90 authKey=${first}`,
    `Mi Smart Band 6 authKey=${first}`,
    `Xiaomi Smart Band 9 Mi Smart Band 6 authKey=${first}`,
    `Xiaomi Smart Band 9 authKey=${first}00`,
    `Xiaomi Smart Band 9 authKey=${'0'.repeat(32)}`,
    '',
  ]) assert.throws(() => extractBand9Key(text));
});
