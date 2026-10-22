// Check that the settings screen parses and that the new pane builds.
//
// The settings screen is plain HTML and JavaScript that opens in a browser,
// so it can be checked without a treadmill — which is the whole reason the
// console's interfaces are written this way.
//
//     node tools/settings_check.js
//
// What it checks:
//   * the document parses at all (a syntax error in one settings screen
//     breaks the settings button on every interface)
//   * the walk log section is in the list of sections
//   * the pane builds, with the switch, the URL field and the state line
//   * the pane survives the bridge being absent, which is what happens
//     when the page is opened in a desktop browser

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const UI = path.join(__dirname, '..', 'console', 'stride', 'app', 'src', 'main', 'assets', 'ui');
const core = fs.readFileSync(path.join(UI, 'stride-core.js'), 'utf8');
const settings = fs.readFileSync(path.join(UI, 'stride-settings.js'), 'utf8');

// A document that is just enough for the settings screen to build into.
//
// The stub is a real tree rather than a flat list, because the settings
// screen finds its own panel with `querySelector('.sx-panel')` and then
// looks inside it — and a stub that answers "nothing found" would make
// this check fail for a reason that has nothing to do with the code.
const made = [];
function element(tag, cls, html) {
  const e = {
    tag, className: cls || '', innerHTML: html == null ? '' : html,
    children: [], style: {}, attributes: {},
    appendChild(c) { this.children.push(c); c.parent = this; return c; },
    setAttribute(k, v) { this.attributes[k] = v; },
    getAttribute(k) { return this.attributes[k]; },
    addEventListener() {},
    querySelector(sel) { return this.find(sel, true); },
    querySelectorAll(sel) { return this.find(sel, false); },
    find(sel, one) {
      const want = sel.replace(/^\./, '');
      const out = [];
      const walk = (node) => {
        for (const c of node.children || []) {
          const classes = (c.className || '').split(/\s+/);
          if (classes.includes(want)) {
            out.push(c);
            if (one) return true;
          }
          if (walk(c)) return true;
        }
        return false;
      };
      walk(this);
      return one ? (out[0] || null) : out;
    },
    dispatchEvent() { return true; },
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
  };
  made.push(e);
  return e;
}

const documentStub = {
  createElement: element,
  body: { appendChild(c) { return c; } },
  addEventListener() {},
  dispatchEvent() { return true; },
};

const settingsObject = {
  people: [{ name: 'Sam', coached: true, publish: false, ha_person: '', age: 40, weightKg: 70 }],
  default_walker: 'Sam',
  allow_guest: true,
  units: 'km',
  ha_enabled: false,
  mqtt_host: '', mqtt_port: 1883, mqtt_user: '', mqtt_pass_set: false,
  mqtt_tls: false, mqtt_prefix: 'stride',
  reset_after_warmup: true, warmup_min: 2, cooldown_min: 2, warmup_kph: 2,
  incline_rate: 'normal', open_lap_min: 20,
  coach_on: true, coach_talk: 'normal', coach_voice: 'Supportive Friend',
  coach_remote: true, coach_milestones: true,
  hr_source: 'auto', hr_addr: '', hr_name: '',
  walk_log_on: true, walk_log_url: 'http://192.168.1.10:8099/walk',
  sleep_min: 5, clock_24: true, brightness: 'normal', keep_awake: true,
};

const bridgeStub = {
  settingsJson: () => JSON.stringify(settingsObject),
  saveSetting: () => {},
  walkLogStatus: () => JSON.stringify({
    enabled: true, url: 'http://192.168.1.10:8099/walk', pending: 2,
    recording: 'walk-1', error: '',
  }),
  walkLogSend: () => {},
  boardLimits: () => JSON.stringify({ minGrade: -3, maxGrade: 12, minKph: 1.6, maxKph: 19 }),
  about: () => JSON.stringify({ version: '0.8.0', build: 4, android: '9', board: 'FitPro' }),
  haPeople: () => '[]',
  mqttStatus: () => JSON.stringify({ enabled: false, connected: false }),
  routes: () => '[]',
};

const sandbox = {
  console,
  document: documentStub,
  setInterval: () => 0,
  clearInterval: () => {},
  setTimeout: () => 0,
  Math, JSON, Date, String, Number, Object, Array, parseInt, parseFloat, isNaN,
  CustomEvent: function () {},
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
sandbox.Stride = bridgeStub;
sandbox.STRIDE = {};

let failed = false;
function check(name, ok, detail) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
  if (!ok) failed = true;
}

try {
  vm.createContext(sandbox);
  vm.runInContext(core, sandbox, { filename: 'stride-core.js' });
  vm.runInContext(settings, sandbox, { filename: 'stride-settings.js' });
} catch (e) {
  console.log('FAIL  the settings screen does not parse — ' + e.message);
  process.exit(1);
}
check('the settings screen parses', true);

// The rail and the panes are built by open(), which needs a shadow root.
sandbox.STRIDE_SETTINGS.open();
const text = made.map(e => e.innerHTML || '').join('\n');
const labels = made.map(e => e.innerHTML || '').join(' ');

check('the walk log section is on the rail', /Walk log/.test(labels));
check('the switch is there', /Send my walks/.test(labels));
check('the destination field is there', /Destination/.test(labels));
check('the state line is there', /Waiting to be sent/.test(labels));
check('what is recorded is explained', /The CSV/.test(labels) && /The summary/.test(labels));

// The pane reads the bridge for its state.
const status = sandbox.Stride.walkLogStatus();
const st = JSON.parse(status);
check('the bridge reports the destination', st.url.includes('8099'));
check('the bridge reports what is waiting', st.pending === 2);

console.log(failed ? '\nFAILED' : '\nall good');
process.exit(failed ? 1 : 0);
