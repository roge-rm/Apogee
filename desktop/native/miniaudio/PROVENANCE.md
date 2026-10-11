# miniaudio 0.11.22, vendored

The desktop build's sound output: one header that opens whatever the machine has, PulseAudio
first on Linux (which PipeWire also answers to), then ALSA, then JACK, and WASAPI on Windows. It
stands in for Oboe, which is Android's. The phone build doesn't include it.

    https://raw.githubusercontent.com/mackron/miniaudio/0.11.22/miniaudio.h
    sha256 9019743287e443c55e5737a7297f38e5e358561701d6db2d905afb114390c410

## Licence

Dual: public domain (the Unlicense) or MIT No Attribution, the licensee's choice, both at the
foot of the header itself. Either is compatible with the GPL this app is under, and neither asks
for a notice.
