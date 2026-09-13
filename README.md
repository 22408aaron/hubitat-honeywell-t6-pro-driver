# Advanced Honeywell T6 Pro Z-Wave Thermostat (Hubitat driver)

A Hubitat Z-Wave driver for the **Honeywell / Resideo T6 Pro** thermostat
(model TH6320ZW2003 / TH6320ZW2007). On top of the full thermostat feature set it adds
remote **Home/Away** control, **programmable Away temperatures**, a **programmed setpoint
with auto-reverting temporary hold**, per-mode/per-fan **enable toggles**, and a
**cool-only/heat-only dashboard fix**.

## Why this driver exists

The stock **Generic Z-Wave Thermostat** driver only controls mode, setpoints and fan.
This driver exposes the rest of what the T6 Pro offers over Z-Wave and adds automation
features that no other T6 driver has.

## Credit / license

Derivative work under the **Apache License 2.0**, built on the **"Advanced Honeywell T6 Pro
Thermostat"** driver by **Bryan Copeland (djdizzyd)**
(<https://github.com/djdizzyd/hubitat>), also Apache-2.0. Additions © 2026 Aaron F. Stone.
See `LICENSE` and `NOTICE`.

## Install

1. Hubitat → **Drivers Code** → **+ New Driver** → **Import** the raw URL, or paste `Advanced-Honeywell-T6-Pro-ZWave.groovy` → **Save**.
2. Devices → your T6 Pro → **Device Info** tab → **Type** → *Advanced Honeywell T6 Pro Z-Wave Thermostat* → **Save Device** (the label should read "Type (currently user)").
3. Click **Configure**, adjust **Preferences**, then **Save Preferences**.

---

# Field reference

Everything the driver exposes, in plain language. In Hubitat these live on three tabs:
**Commands** (buttons you press), **Current States** (read-only values), and **Preferences**
(settings you save).

## Commands (buttons)

**Everyday thermostat**
| Command | What it does |
|---|---|
| Heat / Cool / Auto / Off / Emergency Heat | Set the thermostat mode |
| Set Heating Setpoint / Set Cooling Setpoint | Set the normal (Home) target temps |
| Fan On / Fan Auto / Fan Circulate | Set the fan mode |
| Refresh | Re-read everything from the thermostat now |
| Configure | Re-send settings + associations (run once after install/updates) |

**Home / Away**
| Command | What it does |
|---|---|
| Home | Switch to Home/Comfort (Z-Wave Basic Set) |
| Away | Switch to Away/energy-save |
| Set Away Heating Setpoint / Set Away Cooling Setpoint | Program the Away temps remotely |

**Programmed setpoint + hold**
| Command | What it does |
|---|---|
| Set Programmed Heating Setpoint / Set Programmed Cooling Setpoint | Change the baseline temp live (use in rules to build a time schedule) |
| Resume Schedule | Cancel a temporary hold and return to the programmed temp now |

**Utility**
| Command | What it does |
|---|---|
| Sensor Cal | Calibrate the temperature sensor (−3…+3°, ISU param 42) |
| Idle Brightness | Set the idle screen brightness (0–5, ISU param 39) |
| Sync Clock | Set the thermostat's clock to hub time (also runs every 3 h) |
| Probe Setpoint Types | Log which Z-Wave setpoint types the device supports (Away diagnostics) |

## Current States (read-only)

| State | Meaning |
|---|---|
| temperature / humidity | Current room readings |
| battery | Battery % (0 or absent when on C-wire power) |
| thermostatMode / thermostatOperatingState | Selected mode / what it's doing now (idle, heating, cooling) |
| thermostatFanMode / thermostatFanState | Selected fan mode / whether the fan is running |
| heatingSetpoint / coolingSetpoint / thermostatSetpoint | Current Home targets |
| awayMode | `home` or `away` |
| awayHeatingSetpoint / awayCoolingSetpoint | The programmed Away temps |
| programmedHeatingSetpoint / programmedCoolingSetpoint | Your baseline temps (see hold feature) |
| holdStatus | `following schedule` normally, `temporary hold` after a manual change |
| currentSensorCal / idleBrightness | Current calibration / brightness values |
| powerSource | `mains` or `battery` |

## Preferences (settings)

### Away / energy-save temperatures
- **Away temp Z-Wave type** — which Z-Wave setpoint pair the T6 uses for Away temps.
  **This T6 uses 11/12** — leave it there. (If Away temps stop responding, run *Probe
  Setpoint Types* and read the log.)

### Which modes this device offers
Turn a mode **off** to hide it from dashboards and block it. Multi-select isn't possible in
Hubitat drivers, so each is its own switch. If you turn *all* modes off, all are shown (fail-safe).
- **Offer 'Off' / 'Cool' / 'Heat' / 'Auto' / 'Emergency Heat' mode** — e.g. for a
  cooling-only unit, turn Heat, Auto and Emergency Heat **off**. (Auto needs both Heat and Cool.)

### Which fan modes this device offers
- **Offer fan 'Auto' / 'On' / 'Circulate'** — leave only **Auto** on to effectively lock the
  fan to Auto.

### Programmed setpoint + temporary hold
Program a baseline in Hubitat; a change made *at the thermostat* is kept only temporarily,
then reverts. Works in Heat, Cool, or Auto (it's keyed to the setpoint, not the mode).
- **Enable programmed setpoint + temporary hold** — master on/off for this feature.
- **Programmed HEAT setpoint** — held in Heat mode (and the low limit in Auto). Blank = don't enforce heating.
- **Programmed COOL setpoint** — held in Cool mode (and the high limit in Auto). Blank = don't enforce cooling.
- **Temporary-hold length (minutes)** — how long a manual change is kept before reverting (0 = revert almost immediately).

There is **one field per setpoint** (heat, cool) — not one per mode. **Auto mode uses both:**
heat = the low limit, cool = the high limit.

### Driver logging
- **Enable debug logging** — extra detail in Logs; turns itself off after 30 minutes.

### HVAC installer parameters (42 T6 ISU settings)
These are the thermostat's own equipment/comfort settings, exposed 1:1 from the T6's
Installer Setup. **Most people never touch these** — defaults match a standard system.
Grouped by purpose:

| Group | Parameters | What they cover |
|---|---|---|
| Equipment setup | 1 Schedule Type, 2 Temp Scale, 3 Outdoor Temp, 4 Equipment Type, 5 Reversing Valve, 6 Stages, 7 Heat Stages Aux/E, 8 Aux/E Control, 9 Aux Heat Type, 10 EM Heat Type, 11 Fossil Kit | Tells the T6 what HVAC it controls |
| Staging / cycling / protection | 13 Auto Differential, 14–15 High Stage Finish, 16 Aux Heat Droop, 17 Up-stage Timer, 18 Balance Point, 19 Aux Heat Lockout, 20–25 Cycles-Per-Hour, 26 Compressor Protection | How aggressively equipment stages and cycles |
| Comfort / limits | 12 Auto Changeover, 27 Adaptive Recovery, 28 Min Cool Temp, 29 Max Heat Temp | Comfort behavior and temp limits |
| Maintenance reminders | 30–32 Air Filters, 33 Humidifier Pad, 34 Dehumidifier, 35 Ventilation, 36–38 UV Bulbs | Service reminders (fire Z-Wave notifications) |
| Display / clock / calibration | 39 Idle Brightness, 40 Clock Format, 41 Daylight Savings, 42 Temperature Offset | Screen, clock, and sensor calibration |

> Note: to make a unit **cool-only** or **heat-only** at the equipment level, the cleanest
> route is the thermostat's own installer setup (System Type + set the unused Heat/Cool
> stages to 0). The Z-Wave stage parameters (6/7) are heat-pump-oriented and ambiguous, so
> prefer the thermostat menu for that change.

---

## Common recipes

**Cool-only window unit**
- Turn off modes: Heat, Auto, Emergency Heat (leave Off + Cool).
- Enable programmed setpoint + hold; set **Programmed COOL setpoint** (leave heat blank);
  set hold minutes (e.g. 120).
- The dashboard Thermostat tile works because the driver seeds a placeholder heat setpoint.

**Home/Away automation**
- When everyone leaves → run **Away**; when someone returns → run **Home**.
- Program the Away temps once with **Set Away Cooling/Heating Setpoint**.

**Time-of-day schedule with auto-revert**
- A Rule Machine schedule calls **Set Programmed Cooling Setpoint** at each time block
  (e.g. 72 at 7am, 76 at 9am, 72 at 5pm). Manual bumps at the thermostat auto-revert to the
  current programmed value.

## Files

- `Advanced-Honeywell-T6-Pro-ZWave.groovy` — the driver (install this).
