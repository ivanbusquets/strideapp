# Screenshots

1280×800, straight off the console. Mocked-up numbers, made-up names.

## The interfaces

Each one is a separate document with its own layout — not a theme. The
treadmill behaves the same in all of them.

### Original
A 400 m oval, lap count in the middle.

![Original](screenshots/oval-original.png)

### Ember
Near-black and amber. Built for 6am.

![Ember](screenshots/oval-ember.png)

### Cluster
Dials and mono figures. An instrument panel.

![Cluster](screenshots/oval-cluster.png)

### Daylight
The light one, for a bright room.

![Daylight](screenshots/oval-daylight.png)

### Pacer
Chunky pills, hard to misread mid-stride.

![Pacer](screenshots/oval-pacer.png)

### Super
An instrument panel: incline and speed on stepped keys at either side, the oval
in the middle, one red STOP. Denser than the other five — four telemetry cells
and a heart-rate zone meter instead of one big hero.

## A guided walk

The oval is replaced by the ground ahead — the profile, where you are on it,
and the next segment. STRIDE moves the incline; "lift to 6.4 km/h" is only a
suggestion and the belt doesn't act on it.

![Original, guided walk](screenshots/ui-original.png)

Same walk in Pacer, drawn as a lane:

![Pacer, guided walk](screenshots/ui-pacer.png)

## Settings

On the treadmill's own screen. No config files, no rebuild.

### Who walks
Everyone gets their own totals. Coaching and recording are per person, and can
be linked to a Home Assistant `person`.

![Who walks](screenshots/settings-who-walks.png)

### Heart rate
Bluetooth chest straps, using the standard heart rate service — the scan
filters on service UUID, not brand, so most straps work.

![Heart rate](screenshots/settings-heart-rate.png)

### Display
Kilometres or miles, and everything that comes with living with a screen
that's on all day. Units change every screen and everything published; the
board stays metric underneath either way.

![Display](screenshots/settings-display.png)

### About
The board line is read over USB at startup.

![About](screenshots/settings-about.png)

## Welcome

Nobody is set up out of the box, so it offers a guest walk until you add
people.

![Welcome](screenshots/welcome.png)
