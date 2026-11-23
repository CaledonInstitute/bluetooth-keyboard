/**
 * Converts text to USB HID keyboard key presses (US layout).
 * A key press is a pair [modifier, usage]; lists are flat: [mod, usage, mod, usage, ...].
 */

export const MOD_NONE = 0x00;
export const MOD_SHIFT = 0x02;

export const KEY_ENTER = 0x28;
export const KEY_BACKSPACE = 0x2a;
export const KEY_TAB = 0x2b;
export const KEY_SPACE = 0x2c;

type Stroke = readonly [modifier: number, usage: number];

const KEYMAP = new Map<string, Stroke>();

const LETTERS = 'abcdefghijklmnopqrstuvwxyz';
for (let i = 0; i < LETTERS.length; i++) {
  KEYMAP.set(LETTERS[i], [MOD_NONE, 0x04 + i]);
  KEYMAP.set(LETTERS[i].toUpperCase(), [MOD_SHIFT, 0x04 + i]);
}

const DIGITS = '1234567890';
const DIGIT_SYMBOLS = '!@#$%^&*()';
for (let i = 0; i < DIGITS.length; i++) {
  KEYMAP.set(DIGITS[i], [MOD_NONE, 0x1e + i]);
  KEYMAP.set(DIGIT_SYMBOLS[i], [MOD_SHIFT, 0x1e + i]);
}

// [unshifted, shifted, usage]
const PUNCTUATION: [string, string, number][] = [
  ['-', '_', 0x2d],
  ['=', '+', 0x2e],
  ['[', '{', 0x2f],
  [']', '}', 0x30],
  ['\\', '|', 0x31],
  [';', ':', 0x33],
  ["'", '"', 0x34],
  ['`', '~', 0x35],
  [',', '<', 0x36],
  ['.', '>', 0x37],
  ['/', '?', 0x38],
];
for (const [plain, shifted, usage] of PUNCTUATION) {
  KEYMAP.set(plain, [MOD_NONE, usage]);
  KEYMAP.set(shifted, [MOD_SHIFT, usage]);
}

KEYMAP.set(' ', [MOD_NONE, KEY_SPACE]);
KEYMAP.set('\n', [MOD_NONE, KEY_ENTER]);
KEYMAP.set('\t', [MOD_NONE, KEY_TAB]);

// Typographic characters some phone keyboards insert, mapped to their ASCII keys.
const ALIASES: Record<string, string> = {
  '\u2018': "'",
  '\u2019': "'",
  '\u201c': '"',
  '\u201d': '"',
  '\u2013': '-',
  '\u2014': '-',
};

function strokeFor(char: string): Stroke | undefined {
  return KEYMAP.get(ALIASES[char] ?? char);
}

/** Characters that have no key on a US layout (emoji, accents, ...) are skipped. */
export function textToKeys(text: string): number[] {
  const keys: number[] = [];
  for (const char of text) {
    const stroke = strokeFor(char);
    if (stroke) keys.push(stroke[0], stroke[1]);
  }
  return keys;
}

function countTypeable(text: string): number {
  let count = 0;
  for (const char of text) {
    if (strokeFor(char)) count++;
  }
  return count;
}

/**
 * Key presses needed to turn `prev` into `next` on the laptop, assuming the laptop's
 * cursor sits at the end of `prev`: backspace over the part that changed, then type the new tail.
 * This also covers autocorrect, paste, and edits in the middle of the text.
 */
export function diffToKeys(prev: string, next: string): number[] {
  let common = 0;
  const max = Math.min(prev.length, next.length);
  while (common < max && prev[common] === next[common]) common++;

  const keys: number[] = [];
  const backspaces = countTypeable(prev.slice(common));
  for (let i = 0; i < backspaces; i++) keys.push(MOD_NONE, KEY_BACKSPACE);
  keys.push(...textToKeys(next.slice(common)));
  return keys;
}
