# Cheap Brass Piezo Sensor Guide

This guide describes how to use inexpensive brass piezo disks with Chrono
Logger, how to select matched pairs, and how to validate the setup for the entry
and advanced hardware builds.

## Hardware Builds

Chrono Logger has two expected hardware classes.

| Build | MCU/front-end | Intended use |
|---|---|---|
| Entry | nRF52840, direct GPIO piezo inputs, no buffers | Lowest-cost system; accuracy depends more on sensor matching, clean wiring, and conservative GAE |
| Advanced | nRF52840 with high-speed input buffers/comparators | Better-defined threshold timing; lower analog uncertainty after buffer delay is calibrated |

Both builds benefit from matched sensors. The advanced build reduces the MCU
GPIO threshold and rail-injection uncertainty, but it does not remove sensor
mechanical variation or fixture spacing error.

## What Matching Can and Cannot Fix

Matching sensors by electrical signature is useful because cheap brass piezos
can vary widely. A matched pair tends to have more similar charge behavior,
which reduces threshold-walk differences between the START and STOP channels.

Matching helps with:

1. Large capacitance/load differences between two disks.
2. Different charge/discharge behavior during the port check.
3. Channel mismatch terms used by the GAE calculation.
4. Catching damaged, unstable, or badly soldered sensors before field use.

Matching does not fully fix:

1. Different mechanical strike response between disks.
2. Loose mounting, bending, or different preload.
3. Different wire length, routing, or shielding.
4. Weak or glancing hits that produce slow or marginal edges.
5. Sensor spacing measurement error.

The right goal is not "perfect capacitance." The goal is "repeatable matched
trigger behavior through this exact Chrono input path."

## Sensor Inventory Process

When a new pack of cheap piezos arrives:

1. Assign every disk a permanent ID.
2. Inspect each disk for cracked ceramic, lifted solder tabs, loose wires, or
   visible delamination.
3. Solder or attach equal-length leads before measuring.
4. Use the same connector and lead routing that will be used in the real setup.
5. Run the Chrono port baseline with nothing plugged in.
6. Measure each sensor on the same port at least three times.
7. Repeat a smaller sample on the other port to catch port-specific bias.
8. Record the loaded-minus-bare RC signature in microseconds.
9. Record repeatability, not just the median.
10. Reject sensors that time out, jump around, or produce inconsistent readings.

Recommended inventory fields:

| Field | Example | Why it matters |
|---|---:|---|
| Sensor ID | PZ-014 | Lets logs identify the physical sensor |
| Diameter/type | 27 mm brass disk | Different sizes should not be mixed casually |
| Lead length | 5 ft | Lead length should match within a pair |
| Optional meter capacitance | 30.2 nF | Useful reference, not the primary Chrono value |
| Chrono RC signature | 185.4 us | Primary matching value |
| Stddev/repeatability | 0.18 us | Finds unstable sensors |
| Notes | corner crack | Explains rejects or odd behavior |
| Paired with | PZ-015 | Keeps matched sets together |

The app should continue displaying RC signature/delay rather than claiming a
precise nF value unless a specific fixture calibration exists.

## Pairing Method

After measuring the whole pack:

1. Sort sensors from lowest to highest RC signature.
2. Remove unstable or damaged sensors.
3. Pair nearest neighbors.
4. Keep wire length, connector style, and mounting method the same within each
   pair.
5. Label the pair as a matched set.
6. Recheck the pair installed in the actual START/STOP fixture.
7. Swap START/STOP ports once and recheck.

Pairing by nearest neighbor is better than selecting two sensors that merely
have the same nominal capacitance printed by a meter. Chrono cares about the
threshold behavior of the sensor, wiring, clamp, protection resistor, and input
path as a system.

Good pair target for the entry build:

```text
loaded-minus-bare mismatch <= 600 ns preferred
loaded repeatability stddev <= 250 ns preferred
```

Excellent pair target:

```text
loaded-minus-bare mismatch <= 250 ns
loaded repeatability stddev <= 100 ns
```

If a pair is worse than that, it can still work, but the app should widen the
GAE and the user should avoid treating very fast/short-spacing shots as
high-precision measurements.

## Entry Build: nRF52840 Without Buffers

The entry build uses the piezo signal directly into clamped MCU GPIO inputs.
This makes setup discipline important.

Recommended practices:

1. Use matched pairs from the sorted inventory.
2. Use equal-length leads for START and STOP.
3. Keep leads short when possible; if long leads are required, keep both
   channels the same.
4. Route START and STOP leads together so they see similar noise.
5. Keep the 1 kohm protection resistor and clamp network matched on both
   channels.
6. Re-run port baseline before a session.
7. Re-run loaded channel calibration after replacing sensors.
8. Use the longest practical sensor spacing for the shot.

At 6 inch spacing, the current expected clean-hit timing contribution is about:

| Velocity | Split time | Expected timing-only variability |
|---:|---:|---:|
| 1000 fps | 500 us | about +/-0.2% |
| 3000 fps | 166.7 us | about +/-0.6% |
| 5000 fps | 100 us | about +/-1.0% |

That does not include spacing error. At 6 inches, 1/16 inch spacing error is
already about +/-1.04%.

For the entry build, matched sensors and careful spacing are the two biggest
practical improvements.

## Advanced Build: nRF52840 With Buffers

The advanced build should use high-speed input buffers or comparators between
the piezo front-end and the MCU timer capture input.

Expected advantages:

1. Better-defined switching threshold.
2. Less dependence on the MCU GPIO input threshold.
3. Less rail-injection effect at the MCU pin.
4. Cleaner timing edge into the capture hardware.
5. Easier bench calibration of channel delay.

The advanced build still needs calibration:

```text
channel_delay_offset = buffer_stop_delay - buffer_start_delay
corrected_split = measured_split - channel_delay_offset
```

For each advanced unit, calibrate with clean known-delay pulses before using
piezo sensors. Then repeat with the real piezo front-end attached to determine
the remaining sensor/front-end uncertainty.

Sensor matching still matters because the buffer cannot make two cheap piezo
disks mechanically identical. It only makes the electrical detection threshold
more consistent.

## Stacked Piezo Validation

Stacking insulated piezos close together can be useful, but it should be treated
as a validation and stress test, not the only source of calibration truth.

Only perform projectile validation inside an authorized test range or lab setup,
using the organization's normal remote firing, barriers, PPE, and evidence/log
procedures. This guide addresses measurement behavior only; it does not define
loads, projectiles, stand-off distances, or tactical use.

Concept:

```text
projectile path
    |
    v
first piezo disk
thin insulating spacer
second piezo disk
```

If the physical spacing between the effective trigger planes is known, the
expected split is:

```text
split_time = spacing / projectile_velocity
```

At very small spacing, the split becomes extremely short:

| Spacing | 1000 fps | 3000 fps | 5000 fps |
|---:|---:|---:|---:|
| 1.0 mm | 3.28 us | 1.09 us | 0.66 us |
| 0.5 mm | 1.64 us | 0.55 us | 0.33 us |

This matters because the entry build's normal-use split uncertainty is around
1 us. A sub-1 mm stack at high velocity may be below the useful resolution of
the direct-GPIO front-end. It can still show whether channels trigger in the
right order and whether one sensor is badly delayed, but it should not be used
alone to claim absolute velocity accuracy.

The advanced buffered build is better suited to this kind of short-spacing
validation, but the mechanical uncertainty of the piezo stack can still dominate.
The effective trigger plane may not be exactly the brass surface. It can shift
with disk bending, adhesive, preload, spacer stiffness, and impact location.

Recommended stacked test controls:

1. Use electrical insulation between disks.
2. Measure spacer thickness with a micrometer.
3. Keep the disks coaxial and flat.
4. Keep wire strain off the disks.
5. Use the same lead length on both sensors.
6. Record stack order, port assignment, sensor IDs, and spacer thickness.
7. Shoot multiple repeats.
8. Reverse stack order and repeat.
9. Swap port assignments and repeat.
10. Compare against an independent reference chronograph or optical gate.

Interpretation:

| Observation | Likely meaning |
|---|---|
| Bias follows the Chrono port | Channel electronics delay or threshold difference |
| Bias follows the sensor ID | Sensor electrical/mechanical delay difference |
| Bias follows stack order | Mechanical trigger-plane or impact-order effect |
| Random spread is large | Mounting, impact variation, sensor damage, or weak edge |
| Results agree with longer-spacing reference | Strong validation of the full setup |

The stacked test is most useful after the pack has already been sorted into
matched pairs.

## Calibration Ladder

Use these calibration levels in order.

1. Digital timing check:

```text
Inject known clean delays into START and STOP.
Purpose: prove timer/capture math and channel offset.
```

2. Electrical front-end check:

```text
Inject controlled edges through the protection/clamp path.
Purpose: measure threshold walk versus edge speed.
```

3. Sensor inventory:

```text
Measure every piezo's Chrono RC signature and repeatability.
Purpose: select matched pairs and reject bad sensors.
```

4. Installed pair check:

```text
Run loaded calibration with the pair mounted in the real fixture.
Purpose: catch wiring and mounting changes.
```

5. Stacked piezo validation:

```text
Use a close-spaced stack to stress relative channel timing.
Purpose: expose sensor, port, and stack-order bias.
```

6. End-to-end reference comparison:

```text
Compare against a calibrated optical gate, reference chronograph, or other
traceable timing setup.
Purpose: establish defensible velocity uncertainty.
```

Only the last step can establish end-to-end accuracy for real shots. The earlier
steps make the system more consistent and make the final uncertainty smaller.

## Field Use Checklist

Before a shot:

1. Confirm the build type: entry or advanced.
2. Confirm sensor pair IDs.
3. Confirm START and STOP lead lengths match.
4. Run bare port baseline.
5. Install sensors and run loaded channel calibration.
6. Check channel mismatch and GAE.
7. Measure sensor spacing carefully.
8. Photograph the setup.
9. Record disruptor type/model, loading, projectile type, stand-off distance,
   shot type, and notes.
10. Arm only after the sensor pair and spacing are accepted.

After a shot:

1. Record pass/fail and special notes.
2. Photograph the result.
3. Mark consumed sensors.
4. Replace and recheck sensors before the next shot.
5. Keep the sensor IDs with the shot log if possible.

## Practical Recommendation

For the entry build, the biggest accuracy gains from cheap brass piezos will
come from:

1. Sorting the whole pack by Chrono RC signature.
2. Pairing nearest neighbors.
3. Rejecting unstable sensors.
4. Using equal lead lengths.
5. Increasing sensor spacing where practical.
6. Keeping the app's GAE conservative.

For the advanced build, keep the same sensor workflow but add:

1. Known-delay bench calibration of buffer channel offset.
2. Stored correction per hardware serial number.
3. Verification with stacked sensors and an independent reference.
4. A tighter GAE only after data proves the buffer path is repeatable.

Cheap brass piezos can be useful sensors, but the defensible measurement is not
"this piezo is exactly 25 nF." The defensible measurement is:

```text
this matched sensor pair, on this hardware serial number, with this spacing and
calibration record, produced this velocity with this stated uncertainty.
```
