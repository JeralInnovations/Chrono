# Four-Channel Waterproof Entry Build

This document defines the recommended entry-build hardware layout for a
four-channel Chrono Logger using a Seeed XIAO nRF52840, cheap brass piezo
sensors, a rechargeable 1S battery, and a sealed pogo charging adapter.

The current firmware and app are still two-channel. This document is the
hardware target for the next firmware/app update.

## Design Goals

1. Four piezo input channels.
2. Per-channel RC signature test path.
3. Waterproof enclosure with no exposed USB opening.
4. Rechargeable internal 1S LiPo/Li-ion battery.
5. External pogo charging contacts.
6. Sealed button or magnetic wake/control.
7. Onboard or sealed-window LED indication.
8. Low-leakage storage sleep when not armed.
9. Serviceable enough that the battery is not permanently hard-potted.

## Recommended Pin Map

Use `D0-D3` for the four timing inputs and move the charge/test pins to other
GPIOs. This keeps the timing inputs grouped and leaves a clean mental model for
the connector labels.

| Channel | Sensor input | nRF pin | Charge/test pin | nRF pin |
|---:|---|---|---|---|
| 1 | D0 | P0.02 | D4 | P0.04 |
| 2 | D1 | P0.03 | D5 | P0.05 |
| 3 | D2 | P0.28 | D8 | P1.13 |
| 4 | D3 | P0.29 | D9 | P1.14 |

Support pins:

| Use | Pin | Notes |
|---|---|---|
| User button / wake | D6 / P1.11 | Button to GND with pullup, or sealed reed/Hall equivalent |
| Optional spare / dock detect | D7 / P1.12 | Keep as spare if possible |
| Optional external LED / service signal | D10 / P1.15 | Use only if needed |
| Status LED | Onboard RGB LED | Prefer onboard LED through a window or light pipe |
| Battery measurement | Board battery measurement path | Use existing battery report behavior where supported |

Rationale:

1. The four sensor inputs are exposed pins with direct GPIO capability.
2. Charge pins do not need capture timing, so using `D4/D5/D8/D9` is fine.
3. The button is kept away from the timing inputs.
4. At least one spare pin remains if `D10` is not used.

## Per-Channel Front-End

Each external piezo connector should feed a protection network inside the sealed
box. Do not put the clamp/protection only at the MCU pin with long unprotected
internal wiring.

Recommended per-channel circuit:

```text
external sensor connector pin A
    |
    +-- 1 kohm 1% protection resistor -- sensor node -- MCU sensor input
                                               |
                                               +-- Schottky clamp to 3.3 V
                                               |
                                               +-- Schottky clamp to GND
                                               |
charge/test GPIO -- 10 kohm 1% resistor -------+

external sensor connector pin B -> GND
```

Use the same component values and layout on all four channels. Keep the trace
from connector to protection resistor short, and keep the sensor node compact.

Recommended channel connector wiring:

| Connector pin | Function |
|---|---|
| A | Piezo positive |
| B | Piezo negative / ground |

If using shielded cable, connect shield to enclosure/chassis or quiet ground at
one end only. Do not let shield wiring create different behavior channel to
channel.

## Sensor Connector Strategy

For a waterproof field unit, avoid open audio jacks, exposed Dupont pins, or USB
connectors as sensor ports.

Recommended options:

1. Four small IP67/IP68 two-pin panel connectors.
2. Four potted two-wire pigtails exiting through cable glands.
3. One keyed multi-pin waterproof connector carrying all four channels and
   grounds.

Preferred for serviceability:

```text
four labeled 2-pin waterproof connectors: CH1, CH2, CH3, CH4
```

Preferred for maximum sealing:

```text
potted pigtails with external sacrificial sensor leads
```

Tradeoff:

| Option | Benefit | Risk |
|---|---|---|
| Panel connectors | Easy to replace leads | More sealing surfaces |
| Potted pigtails | Best water seal | Harder field repair |
| One multi-pin connector | Fast setup | One failure affects all channels |

## Rechargeable Battery Architecture

Use a protected 1S LiPo/Li-ion battery connected to the XIAO battery pads or to
a dedicated 1S charger/power-path circuit.

Recommended simple entry build:

```text
pogo +5 V pad -> input protection -> XIAO 5V/VBUS
pogo GND pad  -> GND
1S protected battery -> XIAO BAT pads
XIAO charger handles battery charging when pogo 5 V is present
```

Add on the pogo 5 V input:

1. Current-limited charger/dock supply.
2. Reverse-polarity protection or keyed dock geometry.
3. Small resettable fuse or current limiter.
4. ESD/TVS protection if the pogo pads are exposed.
5. Clear pad labeling on the dock side.

Do not hard-pot the battery. A LiPo can swell or need replacement. Use a
gasketed battery compartment, foam restraint, or removable internal carrier.

Recommended battery size:

```text
500 mAh to 1200 mAh 1S protected LiPo/Li-ion
```

The exact capacity should be chosen after measuring real standby, armed, BLE,
LED, and charging behavior.

## Pogo Charging Adapter

Use pogo contacts for charging instead of a waterproof USB opening.

Minimum charging pads:

| Pad | Function |
|---|---|
| POGO_5V | 5 V charge input |
| POGO_GND | Ground |

Recommended four-pad dock:

| Pad | Function | Reason |
|---|---|---|
| POGO_5V | 5 V charge input | Battery charging |
| POGO_GND | Ground | Return |
| DOCK_DETECT | Optional GPIO or resistor ID | Lets firmware know it is docked |
| SERVICE_GND or NC | Mechanical/contact redundancy | Makes dock more stable |

For sealed field use, keep programming separate from charging unless there is a
strong reason to expose programming pads. A charge-only pogo port is simpler and
more waterproof. Use internal USB/SWD access for development prototypes, then
close the enclosure for field units.

Pogo pad design:

1. Use recessed gold-plated flat pads on the device.
2. Keep enough spacing to avoid water bridging.
3. Mechanically key the dock so polarity cannot be reversed.
4. Put the spring pins in the dock, not the sealed device.
5. Keep the dock unpowered until seated if possible.
6. Use magnets or a shaped cradle for alignment, not the pogo pins alone.
7. Do not charge while the unit is wet unless the dock design is qualified for it.

## Waterproof Enclosure

Recommended construction:

1. Gasketed polycarbonate or nylon enclosure.
2. Stainless screws into inserts or captured nuts.
3. Four labeled sensor connectors or potted pigtails.
4. Recessed pogo charge pads on one side.
5. No exposed USB port.
6. Sealed button, magnetic reed switch, or Hall sensor for user input.
7. Clear LED window or light pipe if visual feedback is needed.
8. Conformal coated PCB.
9. Strain relief for all internal wires.
10. Desiccant pack or hydrophobic vent if temperature swings are expected.

Avoid:

1. Hard-potting the LiPo battery.
2. Relying on hot glue for waterproofing.
3. Exposing live battery voltage on outside contacts.
4. Letting sensor connector shells become part of the measurement path.
5. Different connector/cable types across channels.

## Button and LED

Best waterproof button options:

| Option | Pros | Cons |
|---|---|---|
| Sealed momentary button | Familiar user feel | Penetration through enclosure |
| Magnetic reed switch | No hole through enclosure | Needs magnet, slower feel |
| Hall sensor switch | No hole, robust | Requires tiny standby current |

For the entry build, a sealed momentary button to `D6` is simplest:

```text
D6 -> button -> GND
firmware: INPUT_PULLUP
pressed = LOW
```

Use the onboard RGB LED when possible. If an external LED is required, put it
behind a sealed light pipe or molded window. Do not create an unnecessary leak
path just for the LED.

## Low-Leakage Sleep

When the device is stored or intentionally powered down, release the external
pins:

```text
sensor inputs: input disconnected, no pull, no sense
charge pins: input disconnected, no pull, no drive
LED pins: off
button pin: only wake source, pullup/sense enabled if wake is needed
```

Conceptual firmware behavior:

```cpp
for each external GPIO:
    configure default / disconnected

configure wake button with pullup and sense-low
turn off LEDs
enter System OFF
```

Important distinction:

```text
storage sleep = low leakage, not armed
armed standby = timing hardware awake and watching channels
```

The unit cannot be in lowest-current System OFF while also preserving a
hardware-timer capture of a shot. If the user wants the logger waiting for a
shot, keep GPIOTE/PPI/TIMER active and treat that as armed standby, not storage
sleep.

## Four-Channel Firmware Target

Current firmware is two-channel. The four-channel update should move from
single START/STOP semantics to timestamp capture for all active channels.

Recommended behavior:

1. Arm all four channels.
2. Capture the first rising edge on each channel in hardware.
3. Disable each channel after its first edge to ignore ringing.
4. Store a valid mask showing which channels fired.
5. Use channel 1 as the normal start reference.
6. Report raw offsets for channels 2, 3, and 4 relative to channel 1.
7. Preserve absolute raw timestamps internally for diagnostics.
8. Timeout if not all required channels fire.

Suggested result values:

```text
result_id
epoch
valid_mask
t1_ns = 0
t2_ns = channel2_capture - channel1_capture
t3_ns = channel3_capture - channel1_capture
t4_ns = channel4_capture - channel1_capture
flags
```

The app can then calculate:

```text
segment 1 velocity = spacing_12 / (t2 - t1)
segment 2 velocity = spacing_23 / (t3 - t2)
segment 3 velocity = spacing_34 / (t4 - t3)
overall velocity   = spacing_14 / (t4 - t1)
```

This is better than only reporting one split because the extra channels provide
a sanity check. If one segment disagrees badly, the app can flag possible sensor
failure, odd projectile interaction, or setup error.

## Four-Channel App Target

The app should add a four-channel mode rather than pretending channels 3 and 4
are part of the current two-channel flow.

Setup flow:

1. Port baseline for channels 1-4.
2. Sensor attach/check for channels 1-4.
3. Tap/verify each sensor.
4. Enter spacing:
   - simple mode: equal spacing between all adjacent sensors
   - advanced mode: separate `1-2`, `2-3`, and `3-4` spacings
5. Show channel mismatch across all four sensors.
6. Arm all active channels.
7. Log raw timestamps, segment velocities, overall velocity, GAE, and photos.

Dashboard should show:

```text
CH1 Ready
CH2 Ready
CH3 Ready
CH4 Ready

Spacing 1-2 / 2-3 / 3-4
Overall spacing 1-4
Segment velocity consistency
```

For the entry build, the GAE should remain conservative until four-channel
bench and live validation data exists.

## Build Checklist

Electrical:

1. Four identical protection/clamp networks.
2. Four identical 10 kohm charge paths.
3. Equal sensor connector wiring.
4. Battery connected through protected 1S path.
5. Pogo 5 V input protected and keyed.
6. Button/wake sealed.
7. LED path sealed or internal.
8. Programming access planned before final sealing.

Mechanical:

1. Gasketed enclosure.
2. Sensor connectors torqued and sealed.
3. Pogo pads recessed.
4. Battery retained but replaceable.
5. PCB conformal coated.
6. Cable strain relieved.
7. External labels: CH1, CH2, CH3, CH4, CHARGE.
8. Water test performed before electronics are installed, if possible.

Firmware/app:

1. Four-channel pin map.
2. Four-channel calibration command support.
3. Four-channel status/result BLE packets.
4. Backward compatibility with two-channel hardware or a clear hardware-rev
   split.
5. App setup screens for four sensors.
6. Log/export format for raw timestamps and segment velocities.
7. Battery warning tested on the rechargeable build.
8. Sleep mode tested for leakage with sensors connected and disconnected.

## Recommended Prototype Sequence

1. Build the four-channel board on the bench without waterproof enclosure.
2. Prove all four GPIO captures with clean logic pulses.
3. Prove all four RC charge paths with known capacitors or matched piezos.
4. Update firmware/app protocol.
5. Add battery and pogo charge input.
6. Measure active, armed, BLE idle, charge, and storage currents.
7. Move the proven electronics into the waterproof enclosure.
8. Perform a dry leak check.
9. Perform a powered functional check after sealing.
10. Record the hardware serial, battery size, enclosure revision, and sensor
    connector type in the build log.

## Recommendation

For the waterproof entry build, the best balance is:

```text
4 channels
D0-D3 sensor inputs
D4, D5, D8, D9 charge/test pins
D6 sealed button
onboard LED through a light pipe
1S protected rechargeable battery
external 5 V/GND pogo charge dock
four waterproof 2-pin sensor connectors or potted pigtails
```

This keeps the entry unit compact, sealed, and serviceable while leaving enough
room in the firmware/app design to support segment checks and better error
flagging than a simple two-gate chronograph.

## References

1. Seeed Studio XIAO nRF52840 wiki:
   `https://wiki.seeedstudio.com/XIAO_BLE/`
2. Seeed Studio XIAO nRF52840 pin multiplexing:
   `https://wiki.seeedstudio.com/XIAO-BLE-Sense-Pin-Multiplexing/`
