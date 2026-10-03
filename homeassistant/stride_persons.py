#!/usr/bin/env python3
"""
Publish Home Assistant's person registry where the treadmill can read it.

The console asks a fair question that it cannot answer on its own: when
somebody types "Sam" into Settings, is that the same Sam that Home
Assistant already knows about? Today it is not — the console slugs the typed
name into its own MQTT device, and `person.sam_taylor` and
`stride_person_sam` are unrelated objects that happen to share four letters.

The console cannot look this up itself. **It speaks MQTT and nothing else** —
it holds broker credentials, not a Home Assistant token or URL, deliberately,
because that is one secret and one protocol instead of two. So rather than
teaching the treadmill to call the REST API, Home Assistant puts its own list
on the broker the treadmill is already connected to.

Retained, so a console that boots at 6am gets the list immediately rather than
waiting for whatever next changes it.

    stride/persons
    [{"entity_id": "person.sam_taylor",
      "name": "Sam Taylor",
      "first": "Sam"}, ...]

`first` is there because it is what belongs on a treadmill button. "Sam
Taylor" is correct and too long for a card you tap at 6am with your shoes
half on; the console shows `first` and stores `entity_id`, so the short name is
a label and the link is the identity.

Nothing here decides who may use the treadmill. It is a list of candidates the
console offers under "Add from Home Assistant" — the person doing the adding is
still the person standing in front of it. Somebody who exists only on the
console stays perfectly valid, and is marked there as console-only.

Run:  python3 ha/stride_persons.py
"""
import json
import os
import re
import subprocess
import sys
import urllib.request

from stride_config import HA, conf, mqtt, token


TOPIC = "stride/persons"

# Republished whenever the registry could have changed. A person entity is
# created rarely and lives forever, so this is cheap insurance rather than a
# hot path — and `homeassistant.start` covers the case where the console was
# the thing that restarted.
AUTOMATION_ID = "stride_publish_persons"


# BROKER = conf("mqtt_broker").replace("tcp://", "").split(":")[0]


# def mqtt(topic: str, payload: str):
#     subprocess.run(
#         ["mosquitto_pub", "-r", "-h", BROKER, "-u", MQTT_USER, "-P", MQTT_PASS,
#          "-t", topic, "-m", payload],
#         check=True,
#     )


def persons(tok: str) -> list:
    req = urllib.request.Request(f"{HA}/api/states",
                                 headers={"Authorization": f"Bearer {tok}"})
    states = json.loads(urllib.request.urlopen(req).read())

    out = []
    for s in states:
        if not s["entity_id"].startswith("person."):
            continue
        name = s["attributes"].get("friendly_name") or s["entity_id"].split(".", 1)[1]
        out.append({
            "entity_id": s["entity_id"],
            "name": name,
            # First word only. Everything past it is surname on a console where
            # four people share one.
            "first": name.split()[0],
        })
    return sorted(out, key=lambda p: p["name"])


def automation() -> dict:
    """Republish on start and whenever a person entity changes state.

    A person's *state* changing (home/away) does not change the registry, but it
    is the only cheap trigger Home Assistant offers for "something about the
    people changed", and republishing a retained list of four is free.
    """
    return {
        "alias": "STRIDE — publish person list",
        "description": "Puts Home Assistant's person registry on MQTT so the "
                       "treadmill console can offer them under Add from Home "
                       "Assistant. The console has no HA token by design.",
        "mode": "single",
        "max_exceeded": "silent",
        "triggers": [
            {"trigger": "homeassistant", "event": "start"},
            {"trigger": "state", "entity_id": None, "attribute": "friendly_name"},
        ],
        "actions": [{
            "action": "mqtt.publish",
            "data": {
                "topic": TOPIC,
                "retain": True,
                "payload_template": (
                    "{% set ps = states.person "
                    "| map(attribute='entity_id') | list %}"
                    "[{% for e in ps %}"
                    "{\"entity_id\": \"{{ e }}\", "
                    "\"name\": \"{{ state_attr(e, 'friendly_name') or e }}\", "
                    "\"first\": \"{{ (state_attr(e, 'friendly_name') or e).split(' ')[0] }}\"}"
                    "{{ ', ' if not loop.last }}"
                    "{% endfor %}]"
                ),
            },
        }],
    }


def main():
    tok = token()
    people = persons(tok)
    mqtt(TOPIC, json.dumps(people))
    print(f"  published {len(people)} people to {TOPIC}")
    for p in people:
        print(f"      {p['first']:<10} {p['entity_id']}")

    # The state trigger needs a concrete entity list; build it from what exists.
    cfg = automation()
    cfg["triggers"][1]["entity_id"] = [p["entity_id"] for p in people]

    req = urllib.request.Request(
        f"{HA}/api/config/automation/config/{AUTOMATION_ID}",
        data=json.dumps(cfg).encode(),
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"},
        method="POST")
    print("  automation:", urllib.request.urlopen(req).read().decode())

    urllib.request.urlopen(urllib.request.Request(
        f"{HA}/api/services/automation/reload", data=b"{}",
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"},
        method="POST"))
    print("  reloaded")


if __name__ == "__main__":
    main()
