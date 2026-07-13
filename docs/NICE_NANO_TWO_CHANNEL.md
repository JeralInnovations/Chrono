# nice!nano Two-Channel Build

This is the original Chrono two-channel logger profile. Flash
`firmware/ChronographNiceNano/ChronographNiceNano.ino` using the
JeralInnovations nice!nano v2 Arduino core.

## Connections

| Function | nice!nano connection |
|---|---|
| START input | D0 from the protected CH1 piezo sense node |
| STOP input | D1 from the protected CH2 piezo sense node |
| CH1 RC charge/test | D2 through 10k 1% to CH1 sense node |
| CH2 RC charge/test | D3 through 10k 1% to CH2 sense node |
| Wake/user button | D4 to GND |
| Status LED | GPIO 15, active high, through its LED resistor to GND |
| Battery | Protected 1S LiPo on the nice!nano battery/input rail |

Use the same protected piezo input network described in the root README. The
nice!nano profile reads its VDDH input through `analogReadVDDHDIV5()`, smooths
the value, locks out a new arm at or below 3.40 V, and releases it at or above
3.50 V. The lockout does not interrupt a shot already in progress.

This profile is specifically for a 1S LiPo voltage range. Do not reuse its
percentage curve or lockout thresholds for AA cells.
