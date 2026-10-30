// Check the 1-10 effort (RPE) selector that sits on every summary screen.
//
// The interfaces are plain HTML and JavaScript that open in a browser, so the
// control can be exercised without a treadmill. This loads stride-core.js in a
// small fake DOM, builds the selector the way a page does, taps it, and pushes
// summary frames at it exactly as the 5 Hz render loop would.
//
//     node tools/rpe_check.js
//
// What it checks:
//   * the control builds ten numbers, 1-10, with none selected (no default)
//   * the word under it follows the anchors: 1 easy, 3 moderate, 5 hard,
//     7 very hard, 10 all-out
//   * a tap selects that number, updates the word and asks the console to
//     record it — and only ever with a value in 1..10
//   * a redraw (a summary frame with no rating) does not lose the tap
//   * an out-of-range or absent frame value never clears or sets the selection
//   * resetting between walks clears it without sending anything
//   * all five theme documents carry the same host, wiring and styles

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const UI = path.join(__dirname, '..', 'console', 'stride', 'app', 'src', 'main', 'assets', 'ui');
const THEMES = ['original', 'daylight', 'ember', 'pacer', 'cluster'];

let failed = false;
function check(name, ok, detail) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
  if (!ok) failed = true;
}

/* --- just enough DOM to build and press the control ---------------------- */

function element(tag) {
  const el = {
    tag,
    className: '',
    textContent: '',
    children: [],
    attributes: {},
    listeners: {},
    style: {},
    parent: null,
    appendChild(c) { c.parent = el; el.children.push(c); return c; },
    setAttribute(k, v) { el.attributes[k] = String(v); },
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(el.attributes, k) ? el.attributes[k] : null; },
    addEventListener(type, fn) { (el.listeners[type] = el.listeners[type] || []).push(fn); },
    dispatchEvent() { return true; },
    getBoundingClientRect() { return { x: 0, y: 0, width: 78, height: 72 }; },
    querySelector(sel) { return findAll(el, sel)[0] || null; },
    querySelectorAll(sel) { return findAll(el, sel); },
    classList: {
      names() { return (el.className || '').split(/\s+/).filter(Boolean); },
      set(list) { el.className = list.join(' '); },
      add(c) { const l = el.classList.names(); if (!l.includes(c)) el.classList.set(l.concat(c)); },
      remove(c) { el.classList.set(el.classList.names().filter(n => n !== c)); },
      toggle(c, force) {
        const on = force === undefined ? !el.classList.contains(c) : !!force;
        if (on) el.classList.add(c); else el.classList.remove(c);
        return on;
      },
      contains(c) { return el.classList.names().includes(c); },
    },
  };
  return el;
}

function findAll(root, sel) {
  const want = sel.replace(/^\./, '');
  const out = [];
  (function walk(node) {
    for (const c of node.children || []) {
      if ((c.className || '').split(/\s+/).includes(want)) out.push(c);
      walk(c);
    }
  })(root);
  return out;
}

/** Press something the way a finger does: the control listens for click. */
function press(el) {
  for (const fn of el.listeners.click || []) fn({ preventDefault() {}, type: 'click' });
}

const documentStub = {
  createElement: element,
  addEventListener() {},
  body: { appendChild(c) { return c; } },
};

const sent = [];
const sandbox = {
  console,
  document: documentStub,
  setInterval: () => 0,
  clearInterval: () => {},
  setTimeout: () => 0,
  Math, JSON, Date, String, Number, Object, Array, parseInt, parseFloat, isNaN,
  CustomEvent: function () {},
  Stride: { setRpe: (n) => sent.push(n) },
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;

try {
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(UI, 'stride-core.js'), 'utf8'), sandbox,
                  { filename: 'stride-core.js' });
} catch (e) {
  console.log('FAIL  stride-core.js does not parse — ' + e.message);
  process.exit(1);
}

/* --- the control itself -------------------------------------------------- */

const host = element('div');
sandbox.STRIDE.rpe(host);

const nums = host.querySelectorAll('.rpe-n');
const word = host.querySelector('.rpe-word');

check('ten numbers are built', nums.length === 10, `got ${nums.length}`);
check('they are labelled 1 to 10',
      nums.every((n, i) => n.textContent === String(i + 1)));
check('nothing is selected to begin with',
      nums.every(n => !n.classList.contains('on')));
check('building the control sends nothing', sent.length === 0);
check('the line invites a rating before one is given',
      /how hard/i.test(word.textContent), word.textContent);

const anchors = [[1, 'easy'], [3, 'moderate'], [5, 'hard'], [7, 'very hard'], [10, 'all-out']];
check('the anchors are the agreed words',
      anchors.every(([n, w]) => sandbox.STRIDE.rpeWord(n) === w));

// Tap 7.
press(nums[6]);
check('a tap selects that number',
      nums[6].classList.contains('on') &&
      nums.filter(n => n.classList.contains('on')).length === 1);
check('the word follows the selection', word.textContent === 'very hard', word.textContent);
check('the console is asked to record exactly that number',
      sent.length === 1 && sent[0] === 7, JSON.stringify(sent));

// A summary frame that left before the tap carries no rating. That must not
// wipe the number under the finger.
sandbox.STRIDE.rpeSync(0);
check('a redraw without a rating keeps the selection',
      nums[6].classList.contains('on') && word.textContent === 'very hard');

// Tapping another number moves the selection.
press(nums[2]);
check('tapping another number changes the selection',
      nums[2].classList.contains('on') &&
      !nums[6].classList.contains('on') && word.textContent === 'moderate');
check('the console hears the new number',
      sent[sent.length - 1] === 3, JSON.stringify(sent));

// A console copy (say after a reconnect) with a real rating is adopted.
sandbox.STRIDE.rpeSync(9);
check('a real rating in the frame is adopted', nums[8].classList.contains('on'));

// Nothing outside 1..10 is a rating: 0 means "not given" on the wire.
sandbox.STRIDE.rpeSync(0);
sandbox.STRIDE.rpeSync(11);
check('out-of-range frame values change nothing', nums[8].classList.contains('on'));

// Next walk: the selection belongs to the walk it was given for.
const before = sent.length;
sandbox.STRIDE.rpeReset();
check('a new walk starts unrated', nums.every(n => !n.classList.contains('on')));
check('resetting sends nothing', sent.length === before);
check('the word returns to the invitation', /how hard/i.test(word.textContent));

/* --- every theme carries it ---------------------------------------------- */

for (const theme of THEMES) {
  const html = fs.readFileSync(path.join(UI, theme + '.html'), 'utf8');
  const hosts = html.match(/id="sumRpe"/g) || [];
  check(`${theme}: one selector host in the summary`, hosts.length === 1);
  check(`${theme}: builds the control`,
        /STRIDE\.rpe\(\$\('sumRpe'\)\)/.test(html));
  check(`${theme}: reads the rating from the frame`,
        /STRIDE\.rpeSync\(s\.rpe\)/.test(html));
  check(`${theme}: clears it for the next walk`, /STRIDE\.rpeReset\(\)/.test(html));
  check(`${theme}: styles the numbers, the picked one and the word`,
        /\.rpe-n\b/.test(html) && /\.rpe-n\.on\b/.test(html) && /\.rpe-word\b/.test(html));
}

console.log(failed ? '\nFAILED' : '\nall good');
process.exit(failed ? 1 : 0);
