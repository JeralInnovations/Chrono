# Piezo Input Protection Decision

This note records the earlier low-capacitance prototype recommendation for Chrono piezo
inputs. It is meant to preserve sensitivity and timing repeatability while
keeping large piezo strikes and ESD events out of the nRF52840 pins and the 3.3 V
rail.

The economical two-channel through-hole board now uses `P4KE15CA`, `1k`,
`1N5711`, and a `470k` bleeder. See `ECONOMICAL_TWO_CHANNEL_PTH.md`; that
document is authoritative for the populated PTH board.

## Earlier Prototype Recommendation

Use a two-stage protection network per channel:

```text
sensor connector A / piezo +
    |
    +-- low-cap TVS to GND, placed at connector side
    |
    +-- 2.2k total series resistance, preferably split into two resistors
    |
    +-- sense node ---------------------- nRF52840 timing input
    |        |
    |        +-- Schottky clamp to 3V3
    |        |
    |        +-- Schottky clamp to GND
    |        |
    |        +-- 10k 1% from charge/test GPIO
    |
sensor connector B / piezo - -> GND
```

For the two-channel unbuffered board:

| Item | Recommended starting value |
|---|---|
| Connector-side TVS | Low-cap, low-leakage, 5 V to 6 V standoff ESD/TVS diode to GND |
| Series resistance | 2.2k total, 1%, pulse-rated; use two series resistors if space allows |
| Sense-node rail clamp | Dual Schottky clamp to 3V3 and GND, same part on every channel |
| Charge/test path | 10k 1% from charge GPIO to the sense node |
| Optional tuning footprints | Allow 1k, 2.2k, 3.3k, and 4.7k total series resistance during validation |

This is safer than the original single-stage `1k + rail clamp` approach because
the largest transient current returns to ground near the connector instead of
being dumped into the 3.3 V rail. The rail clamp still protects the MCU input
from the remaining voltage after the series resistor.

## Why 2.2k Is The Starting Point

The existing math document used `1k` as the first estimate. That is sensitive,
but it lets large piezo pulses create high clamp current:

```text
I_clamp ~= (Vpiezo - Vclamp) / Rseries
```

Approximate positive clamp current without the connector-side TVS:

| Open-circuit piezo pulse | 1k series | 2.2k series | 4.7k series |
|---:|---:|---:|---:|
| 30 V | about 26 mA | about 12 mA | about 6 mA |
| 100 V | about 96 mA | about 44 mA | about 21 mA |
| 300 V | about 296 mA | about 135 mA | about 63 mA |
| 500 V | about 496 mA | about 226 mA | about 106 mA |
| 1000 V | about 996 mA | about 453 mA | about 212 mA |

Those are rough pulse-current numbers, not continuous currents. With a
connector-side TVS clamping the raw line first, the rail-clamp current should be
much lower than this table for the largest events.

For example, if the connector-side TVS holds the raw side near 10 V during a
large positive event, the remaining rail-clamp current through 2.2k is roughly:

```text
(10 V - 3.6 V) / 2.2k ~= 2.9 mA
```

That is the reason for using the TVS as the first energy dump and the Schottky
rail clamp as the MCU input guard.

The timing penalty from 2.2k is small if the sense-node capacitance is kept low:

```text
tau = Rseries * Cnode

2.2k * 15 pF ~= 33 ns
4.7k * 15 pF ~= 70 ns
```

That is below the current piezo/front-end uncertainty budget. The risk is not
the resistor by itself; the risk is combining a large resistor with a high-
capacitance clamp at the sense node. Keep the MCU-side clamp low capacitance.

## Part-Selection Rules

Connector-side TVS:

1. Put this near the sensor connector.
2. Route its return directly to ground with a short via path.
3. Prefer very low capacitance, roughly 1 pF or less when available.
4. Use it as surge/ESD protection, not as the only MCU input clamp.
5. Avoid high-capacitance 3.3 V TVS parts on the sense node.

Sense-node rail clamp:

1. Use Schottky steering diodes so the external clamp turns on before the MCU's
   internal protection diodes.
2. A BAT54S-style dual Schottky clamp is a good robust starting point.
3. Lower-capacitance Schottky parts can be tested if scope data shows the BAT54S
   capacitance is moving the threshold crossing.
4. A low-leakage silicon diode such as BAV199 is attractive for capacitance and
   leakage, but its higher forward voltage makes it less ideal as the only GPIO
   rail clamp.

Series resistor:

1. Start at 2.2k total.
2. Use the same total value and package style on every channel.
3. Prefer two series resistors when space allows; this improves pulse voltage and
   energy margin.
4. Validate 1k, 2.2k, 3.3k, and 4.7k on the bench before freezing the final
   value.

3.3 V rail:

1. Place local decoupling near the clamp return point.
2. Keep the clamp current loop away from the BLE antenna and timing traces.
3. Watch 3.3 V rail movement on a scope during hard piezo strikes. Rail movement
   is timing error waiting to happen.

## Layout Rules

1. The TVS belongs at the connector side.
2. The series resistor belongs between the raw piezo side and the sense node.
3. The rail clamp belongs at the sense node, close to the MCU input.
4. The charge/test resistor connects to the sense node, not the raw connector
   side.
5. Keep CH1 and CH2 physically matched.
6. Add test pads for raw piezo node, sense node, 3V3, and GND on both channels.

## Validation Plan

Before freezing the PCB:

1. Build one channel with selectable 1k, 2.2k, 3.3k, and 4.7k series values.
2. Scope raw piezo node, sense node, and 3.3 V rail during normal and hard
   strikes.
3. Confirm no MCU resets, no rail lift large enough to move threshold behavior,
   and no missed low-energy triggers.
4. Compare channel-to-channel trigger timing with a common injected pulse.
5. Run the app's bare and loaded RC checks with the final clamp network.
6. Repeat after several hard strikes to make sure leakage or clamp damage did
   not shift the RC signature.

## Earlier Direction

For the first two-channel unbuffered PCB, use:

```text
low-cap connector TVS to GND
2.2k total series resistance
BAT54S-style Schottky rail clamp at the sense node
10k charge/test resistor to the sense node
matched layout and test pads on both channels
```

For the four-channel buffered PCB, keep the connector TVS and series resistor,
but move the timing decision into the comparator/buffer input. That board should
earn any tighter accuracy claim from measured channel-delay and threshold-walk
data, not from the schematic alone.
