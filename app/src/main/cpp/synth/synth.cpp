#include "synth.h"

#include <cstring>

namespace apogee {

namespace {

/** Samples between recomputing a voice's filter coefficients. */
constexpr int kControlInterval = 32;

bool continuous(int r) { return r <= recipe::LAST_CONTINUOUS; }

/** How long a one-shot is allowed to ring, seconds, before it is cut. */
float lifetime(int r, const float* p) {
    switch (r) {
        case recipe::IMPACT: return (p[1] == material::METAL ? 2.0f : 0.9f) + p[2];
        case recipe::CRUNCH: return 1.1f;
        case recipe::TEAR: return 1.3f;
        case recipe::EXPLOSION: return 2.5f + 4.0f * p[0];
        case recipe::SPLASH: return 1.6f;
        case recipe::STAGE: return 1.2f;
        case recipe::THUNDER: return 9.0f;
        case recipe::CLICK: return 0.08f;
        case recipe::CAUTION: return 0.36f;
        case recipe::IGNITION: return 1.6f;
        case recipe::CHUTE: return 1.3f;
        case recipe::CLUNK: return 0.7f;
        case recipe::SLAP: return 0.6f;
        default: return 1.0f;
    }
}

/**
 * A factor near 1, drawn from the voice's own seed: [amount] either way.
 * What keeps the tenth blow from sounding like the first.
 */
float vary(Rng& rng, float amount) { return 1.0f + amount * (2.0f * rng.uniform() - 1.0f); }

/**
 * The material's ringing modes, scaled for [size] - and, given [rng], each
 * nudged a little in pitch and level, so no two blows ring quite alike.
 */
void setMaterial(Resonators& res, int m, float size, float sr, Rng* rng = nullptr) {
    float scale = 1.4f - 0.8f * clampf(size, 0.0f, 1.0f);
    float f[6], q[6], g[6];
    int n;
    switch (m) {
        case material::METAL: {
            const float F[] = {210, 530, 1240, 2650, 4100}, Q[] = {25, 30, 35, 40, 40}, G[] = {1, .8f, .6f, .4f, .25f};
            n = 5; std::memcpy(f, F, sizeof F); std::memcpy(q, Q, sizeof Q); std::memcpy(g, G, sizeof G); break;
        }
        case material::ROCK: {
            const float F[] = {95, 260, 700, 1600}, Q[] = {6, 8, 10, 12}, G[] = {1, .7f, .5f, .3f};
            n = 4; std::memcpy(f, F, sizeof F); std::memcpy(q, Q, sizeof Q); std::memcpy(g, G, sizeof G); break;
        }
        case material::WOOD: {
            const float F[] = {180, 420, 900}, Q[] = {10, 12, 14}, G[] = {1, .6f, .35f};
            n = 3; std::memcpy(f, F, sizeof F); std::memcpy(q, Q, sizeof Q); std::memcpy(g, G, sizeof G); break;
        }
        case material::SAND: case material::SNOW: {
            const float F[] = {60, 170}, Q[] = {1.3f, 1.6f}, G[] = {1, .5f};
            n = 2; std::memcpy(f, F, sizeof F); std::memcpy(q, Q, sizeof Q); std::memcpy(g, G, sizeof G); break;
        }
        default: {  // earth
            const float F[] = {60, 150, 380}, Q[] = {2, 3, 3}, G[] = {1, .6f, .3f};
            n = 3; std::memcpy(f, F, sizeof F); std::memcpy(q, Q, sizeof Q); std::memcpy(g, G, sizeof G); break;
        }
    }
    float overall = rng ? vary(*rng, 0.06f) : 1.0f;
    for (int i = 0; i < n; ++i) {
        f[i] *= scale * overall * (rng ? vary(*rng, 0.03f) : 1.0f);
        if (rng) g[i] *= vary(*rng, 0.3f);
    }
    res.set(n, f, q, g, sr);
}

void setMetal(Resonators& res, float scale, float sr, Rng* rng = nullptr) { setMaterial(res, material::METAL, 1.4f - scale, sr, rng); }

/**
 * Each recipe's level, set by measuring it in the gallery with the limiter
 * off: a held sound at full strength sits near -18 dBFS RMS, a one-shot
 * peaks near -6, so a scene full of them mixes without the limiter working.
 * Engines sit lower again, near -26: they run for minutes at a time, and
 * at the level of a crash they wear on the ear (Dan, on his phone). Wind
 * and airflow sit about 12 dB under the rest: beside the quieter engines
 * they were intrusive.
 *
 * Listening fatigue is the rule for every sound: listenable over accurate.
 * `./gradlew :app:soundGallery -Praw` prints each sound's loudness as heard
 * through a phone speaker ("phone" LUFS) and its energy per octave. Engines
 * at full power sit at -29..-31 there, the rocket being the reference;
 * long ambient sounds 4 dB or more under them; alerts no more than ~5 dB
 * over. Anything held keeps its energy under ~1.5 kHz, with 4 kHz and up
 * 10 dB or more under its loudest octave: no sustained high whines or
 * white hiss. Anything that repeats varies, never a beat.
 */
float trim(int r) {
    switch (r) {
        case recipe::ROCKET: return 0.11f;
        case recipe::JET: return 0.115f;   // just under a rocket: flights are long (Dan)
        case recipe::PROP: return 0.22f;
        case recipe::AIRFLOW: return 0.075f;
        case recipe::REENTRY: return 0.29f;
        case recipe::WIND: return 0.1f;
        case recipe::RAIN: return 0.24f;  // under the engines: it lasts
        case recipe::CABIN: return 0.36f;
        case recipe::STRESS: return 0.14f;  // a fifth of before: under the engines, not over them (Dan)
        case recipe::FIRE: return 0.19f;
        case recipe::ROVER: return 0.19f;  // was 10 dB over a rocket at full power
        case recipe::OUTBOARD: return 0.27f;
        case recipe::SURF: return 0.33f;  // ambient: well under the engines
        case recipe::SEA: return 0.39f;  // ambient, and long: 6-7 dB under the engines rough
        case recipe::RCS: return 0.25f;  // puffs under the engines
        case recipe::IMPACT: return 0.2f;
        case recipe::CRUNCH: return 0.09f;
        case recipe::TEAR: return 0.18f;
        case recipe::EXPLOSION: return 0.21f;
        case recipe::SPLASH: return 0.7f;
        case recipe::STAGE: return 0.05f;  // about 10 dB down: the stage button was still far too loud (Dan)
        case recipe::THUNDER: return 0.19f;
        case recipe::CLICK: return 0.15f;  // a hint of a tick, no more (Dan: far too loud)
        case recipe::CAUTION: return 0.8f;  // a clear step over the engines, not 10 dB
        case recipe::IGNITION: return 0.12f;
        case recipe::CHUTE: return 0.66f;
        case recipe::CLUNK: return 0.25f;
        case recipe::SLAP: return 0.2f;  // a knock under the engines, not a bang
        default: return 1.0f;
    }
}

/** Metal rings far harder than earth thuds: each material's own balance in an impact. */
float materialGain(int m) {
    switch (m) {
        case material::METAL: return 0.45f;
        case material::ROCK: return 1.0f;
        case material::WOOD: return 0.8f;
        case material::SAND: case material::SNOW: return 2.2f;
        default: return 1.8f;  // earth
    }
}

}  // namespace

Synth::Synth(float sampleRate, int voiceBudget)
    : sampleRate_(sampleRate), voiceBudget_(std::min(voiceBudget, kMaxVoices)) {
    for (int b = 0; b < bus::COUNT; ++b) {
        busTarget_[b].store(1.0f);
        bus_[b].setTime(0.05f, sampleRate);
        bus_[b].value = 1.0f;
    }
    room_.setTime(0.3f, sampleRate);
    room_.value = 0.15f;
    // Freeverb's tunings, scaled to this rate, one side offset for width.
    const int combs[4] = {1116, 1188, 1277, 1356};
    const int allpasses[2] = {556, 441};
    float scale = sampleRate / 44100.0f;
    for (int c = 0; c < 2; ++c) {
        for (int i = 0; i < 4; ++i) comb_[c][i].assign(static_cast<size_t>((combs[i] + c * 23) * scale), 0.0f);
        for (int i = 0; i < 2; ++i) allpass_[c][i].assign(static_cast<size_t>((allpasses[i] + c * 23) * scale), 0.0f);
    }
}

void Synth::publishScene(const Scene& scene) {
    buffers_[writing_] = scene;
    writing_ = ready_.exchange(writing_ | 4) & 3;
}

bool Synth::pushEvent(const Event& event) {
    uint32_t head = eventHead_.load(std::memory_order_relaxed);
    uint32_t tail = eventTail_.load(std::memory_order_acquire);
    if (head - tail >= kEventSlots) return false;
    events_[head % kEventSlots] = event;
    eventHead_.store(head + 1, std::memory_order_release);
    return true;
}

void Synth::setBusGains(const float* gains) {
    for (int b = 0; b < bus::COUNT; ++b) busTarget_[b].store(clampf(gains[b], 0.0f, 2.0f));
}

int Synth::activeVoices() const {
    int n = 0;
    for (int i = 0; i < voiceBudget_; ++i) if (voices_[i].active) ++n;
    return n;
}

Voice* Synth::allocate() {
    Voice* quietest = nullptr;
    for (int i = 0; i < voiceBudget_; ++i) {
        Voice& v = voices_[i];
        if (!v.active) return &v;
        if (!quietest || v.loudness < quietest->loudness) quietest = &v;
    }
    // Full: the quietest makes way.
    return quietest;
}

void Synth::startVoice(Voice& v, int r, int flags, const float* p, uint32_t seed, bool held) {
    float sr = sampleRate_;
    v = Voice();
    v.active = true;
    v.held = held;
    v.recipe = r;
    v.flags = flags;
    v.rng.seed(seed ? seed : nextSeed_++ * 2654435761u);
    for (int i = 0; i < kParams; ++i) {
        v.target[i] = p[i];
        v.param[i].setTime(0.04f, sr);
        v.param[i].value = p[i];
    }
    v.fade.setTime(continuous(r) ? 0.06f : 0.002f, sr);
    v.fade.value = continuous(r) ? 0.0f : 1.0f;
    v.distance.set(p[P_LOWPASS] > 0 ? p[P_LOWPASS] : 20000.0f, sr);
    v.hullLow.set(600.0f, sr);

    switch (r) {
        case recipe::IMPACT: {
            float energy = p[0], size = p[2];
            int m = static_cast<int>(p[1] + 0.5f);
            setMaterial(v.res, m, size, sr, &v.rng);
            v.env[0].trigger(0.0005f, (0.004f + 0.02f * size) * vary(v.rng, 0.25f), sr);
            v.env[1].trigger(0.002f, (0.12f + 0.25f * size) * vary(v.rng, 0.2f), sr);
            v.state[0] = 150.0f * vary(v.rng, 0.12f);  // thump, sweeping down
            v.state[1] = std::min(1.5f, 0.25f + energy);
            v.f[0].set(m == material::ROCK ? 1600.0f : 900.0f, 0.7f, sr);
            break;
        }
        case recipe::CRUNCH:
            setMetal(v.res, 0.8f, sr, &v.rng);
            v.env[0].trigger(0.001f, 0.25f * vary(v.rng, 0.25f), sr);
            v.state[0] = vary(v.rng, 0.08f);
            v.state[1] = std::min(1.5f, 0.3f + p[0]);
            v.f[0].set(600.0f, 0.7f, sr);
            break;
        case recipe::TEAR:
            v.env[0].trigger(0.01f, 0.35f * vary(v.rng, 0.25f), sr);
            v.env[1].trigger(0.0005f, 0.006f, sr);
            setMetal(v.res, 0.9f, sr, &v.rng);
            v.state[0] = 3200.0f * vary(v.rng, 0.15f);
            v.state[1] = std::min(1.3f, 0.3f + p[0]);
            break;
        case recipe::EXPLOSION: {
            float size = clampf(p[0], 0.1f, 1.5f);
            v.env[0].trigger(0.0003f, 0.012f, sr);
            v.env[1].trigger(0.01f, (0.8f + 2.2f * size) * vary(v.rng, 0.2f), sr);
            v.env[2].trigger(0.2f, (2.5f + 2.0f * size) * vary(v.rng, 0.25f), sr);
            v.f[0].set(900.0f, 0.7f, sr);
            v.f[1].set(110.0f + 60.0f * (1.0f - size), 0.7f, sr);
            v.f[2].set(1500.0f, 1.2f, sr);
            v.f[3].set((420.0f - 120.0f * size) * vary(v.rng, 0.15f), 0.8f, sr);
            v.state[1] = 0.6f + 0.6f * size;
            break;
        }
        case recipe::SPLASH:
            v.env[0].trigger(0.002f, (0.08f + 0.2f * p[0]) * vary(v.rng, 0.25f), sr);
            v.f[0].set(1200.0f * vary(v.rng, 0.2f), 0.5f, sr);
            v.state[1] = std::min(1.2f, 0.3f + p[0]);
            break;
        case recipe::STAGE:
            v.env[0].trigger(0.0002f, 0.006f, sr);
            v.env[1].trigger(0.0005f, 0.01f, sr);
            v.f[0].set(1500.0f * vary(v.rng, 0.15f), 0.7f, sr);
            setMetal(v.res, 0.4f, sr, &v.rng);
            break;
        case recipe::THUNDER: {
            float close = clampf(p[0], 0.0f, 1.0f);
            v.env[0].trigger(0.001f, 0.05f, sr, close * close);
            v.env[1].trigger(0.3f, (3.0f + 3.0f * (1.0f - close)) * vary(v.rng, 0.3f), sr);
            v.f[0].set(1200.0f, 0.7f, sr);
            v.f[1].set(90.0f + 60.0f * close, 0.7f, sr);
            v.f[2].set(260.0f + 200.0f * close, 0.8f, sr);
            v.state[0] = 0.7f;
            v.state[1] = 0.7f;
            break;
        }
        case recipe::CLICK:
            v.env[0].trigger(0.002f, 0.01f, sr);
            break;
        case recipe::CAUTION:
            break;
        case recipe::IGNITION:
            v.env[0].trigger(0.03f, 0.5f + 0.6f * p[0], sr);
            v.state[0] = 150.0f;
            break;
        case recipe::CHUTE:
            v.env[0].trigger(0.05f, 0.4f, sr);
            v.state[0] = 300.0f;
            break;
        case recipe::SLAP:
            // A hull meeting a wave: a short dull slap and the thump of the boat.
            v.env[0].trigger(0.001f, (0.04f + 0.08f * p[0]) * vary(v.rng, 0.25f), sr);
            v.env[1].trigger(0.002f, 0.12f * vary(v.rng, 0.2f), sr);
            v.f[0].set(480.0f * vary(v.rng, 0.2f), 0.6f, sr);
            v.state[0] = 80.0f * vary(v.rng, 0.15f);
            v.state[1] = std::min(1.0f, 0.3f + p[0]);
            break;
        case recipe::CLUNK: {
            float k = vary(v.rng, 0.08f);
            const float F[] = {300 * k, 800 * k * vary(v.rng, 0.03f), 1900 * k * vary(v.rng, 0.03f)}, Q[] = {15, 15, 18};
            const float G[] = {1, .6f * vary(v.rng, 0.3f), .3f * vary(v.rng, 0.3f)};
            v.res.set(3, F, Q, G, sr);
            v.env[0].trigger(0.0005f, 0.003f, sr);
            v.env[1].trigger(0.001f, 0.08f, sr);
            break;
        }
        default:
            break;
    }
}

void Synth::takeScene() {
    int ready = ready_.load(std::memory_order_acquire);
    if (!(ready & 4)) return;
    reading_ = ready_.exchange(reading_) & 3;
    const Scene& scene = buffers_[reading_];

    // Everything held is let go unless the scene still has it.
    for (int i = 0; i < voiceBudget_; ++i) if (voices_[i].active && continuous(voices_[i].recipe)) voices_[i].held = false;
    for (int e = 0; e < scene.count && e < kMaxSceneEntries; ++e) {
        const SceneEntry& entry = scene.entries[e];
        Voice* found = nullptr;
        for (int i = 0; i < voiceBudget_; ++i) {
            Voice& v = voices_[i];
            if (v.active && continuous(v.recipe) && v.key == entry.key && v.recipe == entry.recipe) { found = &v; break; }
        }
        if (!found) {
            found = allocate();
            startVoice(*found, entry.recipe, entry.flags, entry.p, static_cast<uint32_t>(entry.key) * 2654435761u, true);
            found->key = entry.key;
        }
        found->held = true;
        found->flags = entry.flags;
        std::memcpy(found->target, entry.p, sizeof entry.p);
    }
}

void Synth::takeEvents() {
    uint32_t tail = eventTail_.load(std::memory_order_relaxed);
    uint32_t head = eventHead_.load(std::memory_order_acquire);
    while (tail != head) {
        const Event& e = events_[tail % kEventSlots];
        Voice* v = allocate();
        startVoice(*v, e.recipe, e.flags, e.p, e.seed, false);
        v->delaySamples = static_cast<int>(e.delay * sampleRate_);
        ++tail;
    }
    eventTail_.store(tail, std::memory_order_release);
}

void Synth::renderVoice(Voice& v, float& left, float& right) {
    left = right = 0;
    if (v.delaySamples > 0) { --v.delaySamples; return; }
    const float sr = sampleRate_;
    const float dt = 1.0f / sr;
    float p[kParams];
    for (int i = 0; i < kParams; ++i) p[i] = v.param[i].next(v.target[i]);
    const bool control = (static_cast<int>(v.state[7]) % kControlInterval) == 0;
    v.state[7] += 1.0f;
    if (v.state[7] > 1e6f) v.state[7] = 0;
    Rng& rng = v.rng;
    float s = 0;
    // Doppler, for what moves: engines and wheels shift every frequency they have.
    const float pf = p[P_PITCH] > 0 ? clampf(p[P_PITCH], 0.5f, 2.0f) : 1.0f;

    switch (v.recipe) {
        case recipe::ROCKET: {
            float out = clampf(p[0], 0, 1), size = clampf(p[1], 0, 1), press = clampf(p[2], 0, 1), crack = clampf(p[3], 0, 1);
            // The engine's own character: 0 a sea-level booster - rough,
            // crackling, deep - to 1 an engine built for vacuum, whose big
            // bell smooths its flow: a steadier, higher, softer roar.
            float vac = clampf(p[4], 0, 1);
            if (control) {
                v.f[0].set(lerpf(350, 2600, press) * (1.2f - 0.5f * size) * pf, 0.6f, sr);
                v.f[1].set(lerpf(900, 1600, press) * (1.1f - 0.4f * size) * pf, 1.2f, sr);
                v.f[2].set((55.0f + 40.0f * (1.0f - size)) * pf, 0.7f, sr);
                // The body of the roar, where a phone's speaker can carry it.
                v.f[3].set(lerpf(260, 520, press) * (1.2f - 0.4f * size) * (1.0f + 0.35f * vac) * pf, 0.8f + 0.6f * vac, sr);
            }
            float w = rng.white();
            float pinkNoise = v.pink.next(w);
            v.f[0].process(v.brown.next(w) * 0.6f + pinkNoise * 0.5f);
            v.f[1].process(v.pink2.next(rng.white()));
            v.f[2].process(v.brown2.next(rng.white()));
            v.f[3].process(pinkNoise);
            float rough = crack * (1.0f - 0.75f * vac);
            float c = v.crackle.next(rng, (40.0f + 160.0f * rough) * pf, sr) * rough * press;
            s = (v.f[0].low * 0.7f + v.f[3].band * (2.2f + 0.8f * vac) + v.f[1].band * 0.9f * (0.3f + 0.7f * press) + c * 0.8f
                 + v.f[2].low * (0.5f + 0.6f * size) * (1.0f - 0.5f * vac)) * std::pow(out, 0.7f)
                * (1.0f - 0.4f * vac);  // a different sound, not a louder one
            break;
        }
        case recipe::JET: {
            float out = clampf(p[0], 0, 1), spool = clampf(p[1], 0, 1), air = clampf(p[2], 0, 1);
            // Heard from inside, through the airframe: the turbine's whine is
            // there but muffled, well under the roar. It used to climb to
            // 4.6 kHz with a harmonic at 9 - true to a jet on the apron, and
            // tiring within a minute (Dan: too high, too hard to listen to).
            float f = (260.0f + 1000.0f * spool) * pf;
            float tone = v.osc[0].sine(f, sr) * 0.12f + v.osc[1].sine(f * 2.01f, sr) * 0.03f + v.osc[2].sine(f * 0.5f, sr) * 0.1f;
            if (control) {
                v.f[0].set((300.0f + 700.0f * spool) * pf, 0.7f, sr);
                v.f[1].set((500.0f + 300.0f * air) * pf, 1.5f, sr);
                // The roar's body, low where a phone speaker still carries it.
                v.f[2].set((180.0f + 160.0f * spool) * pf, 0.8f, sr);
                // And everything through the hull: little above a kilohertz or so.
                v.f[3].set((900.0f + 500.0f * spool) * pf, 0.6f, sr);
            }
            float w = rng.white();
            v.f[0].process(v.pink.next(w));
            v.f[1].process(v.pink2.next(rng.white()));
            v.f[2].process(v.brown.next(w));
            float raw = tone * (0.3f + 0.7f * spool) + v.f[0].low * 1.2f + v.f[1].band * 0.5f * air + v.f[2].band * 1.5f;
            v.f[3].process(raw);
            s = v.f[3].low * out;
            break;
        }
        case recipe::PROP: {
            float out = clampf(p[0], 0, 1), rate = clampf(p[1], 5, 400) * pf, load = clampf(p[2], 0, 1);
            if (control) { v.f[0].set((900.0f + 900.0f * load) * pf, 0.8f, sr); v.f[1].set(300.0f * pf, 0.7f, sr); }
            v.f[0].process(v.osc[0].pulse(rate, 0.15f, sr));
            v.f[1].process(v.osc[1].saw(rate * 0.5f, sr) * (0.7f + 0.3f * rng.white()));
            s = (v.f[0].low * 0.5f + v.f[1].low * 0.5f + v.brown.next(rng.white()) * 0.15f) * out;
            break;
        }
        case recipe::AIRFLOW: {
            float loud = clampf(p[0], 0, 1.5f), speed = clampf(p[1], 0, 1), buffet = clampf(p[2], 0, 1);
            if (control) { v.f[0].set(250.0f + 2200.0f * speed, 0.8f, sr); v.f[1].set(140.0f, 1.2f, sr); }
            v.f[0].process(v.pink.next(rng.white()));
            v.f[1].process(v.brown.next(rng.white()));
            s = v.f[0].band * 3.0f * loud + v.f[1].band * 0.6f * buffet * loud;
            break;
        }
        case recipe::REENTRY: {
            float loud = clampf(p[0], 0, 1.5f), heat = clampf(p[1], 0, 1);
            if (control) { v.f[0].set(250.0f + 350.0f * heat, 0.7f, sr); v.f[1].set(1800.0f, 1.5f, sr); v.f[2].set(450.0f + 300.0f * heat, 0.9f, sr); }
            v.f[0].process(v.brown.next(rng.white()));
            v.f[1].process(v.crackle.next(rng, 150.0f + 400.0f * heat, sr));
            v.f[2].process(v.pink.next(rng.white()));
            s = (v.f[0].low * 1.0f + v.f[2].band * 2.5f + v.f[1].band * 1.5f * heat) * loud;
            break;
        }
        case recipe::WIND: {
            float loud = clampf(p[0], 0, 1.5f), gust = clampf(p[1], 0, 1), bright = clampf(p[2], 0, 1);
            // Gusts: a slow wander toward a new strength every second or so.
            v.state[2] -= dt;
            if (v.state[2] <= 0) { v.state[1] = rng.uniform(); v.state[2] = 0.4f + 1.2f * rng.uniform(); }
            v.state[0] += (v.state[1] - v.state[0]) * (dt / 0.6f);
            // It breathes: gusts swell and fall away, never a steady hiss.
            float swell = v.state[0] * v.state[0];
            float g = 0.15f + 0.85f * ((1.0f - gust) * 0.5f + gust * swell * 1.8f);
            if (control) { v.f[0].set(150.0f + 450.0f * bright + 350.0f * swell * gust, 1.2f, sr); v.f[1].set(110.0f, 0.8f, sr); }
            v.f[0].process(v.pink.next(rng.white()));
            v.f[1].process(v.brown.next(rng.white()));
            s = (v.f[0].band * 1.8f + v.f[1].low * 0.8f) * g * loud;
            break;
        }
        case recipe::RAIN: {
            float loud = clampf(p[0], 0, 1.5f), patter = clampf(p[1], 0, 1);
            // A soft wash with the drops ticking in it, not a hiss: it can go
            // on for minutes, and white noise at 5 kHz wears within one.
            if (control) { v.f[0].set(1600.0f, 0.6f, sr); v.f[1].set(2400.0f, 3.0f, sr); }
            v.f[0].process(v.pink.next(rng.white()));
            v.f[1].process(v.crackle.next(rng, 30.0f + 120.0f * patter, sr));
            s = (v.f[0].low * 0.8f + v.f[1].band * patter * 2.0f) * loud;
            break;
        }
        case recipe::CABIN: {
            float loud = clampf(p[0], 0, 1.5f), settling = clampf(p[1], 0, 1), humLevel = clampf(p[2], 0, 1);
            float hum = v.osc[0].sine(60, sr) * 0.3f + v.osc[1].sine(120, sr) * 0.15f + v.osc[2].sine(180, sr) * 0.05f;
            if (control) v.f[0].set(700.0f, 0.7f, sr);
            v.f[0].process(v.pink.next(rng.white()));
            // The hull ticks as it settles to the pressure outside: often while
            // the air thins on the way up, dying away once it levels out, and
            // almost never once it has settled in space. Mostly faint, never the
            // same note twice (Dan: a steady tick read as a wood block).
            // state[0] is the work left before the next tick, in mean gaps;
            // state[1] arms it; state[2] keeps ticks from bunching up.
            // At most one every eight seconds or so, even climbing hard
            // (Dan: still too many pings on the way up).
            float rate = 1.0f / 300.0f + settling * 0.12f;  // ticks a second
            if (v.state[1] == 0) { v.state[1] = 1; v.state[0] = -std::log(1.0f - 0.95f * rng.uniform()); }
            v.state[0] -= dt * rate;
            v.state[2] -= dt;
            if (v.state[0] <= 0 && v.state[2] <= 0) {
                // Sometimes a second settles right after; otherwise a wait.
                bool runOn = settling > 0.2f && rng.uniform() < 0.25f;
                v.state[0] = runOn ? 0.1f : -std::log(1.0f - 0.95f * rng.uniform());
                v.state[2] = runOn ? 0.25f : 0.8f;
                // A thin metal ping, not a knock: bar-like inharmonic modes.
                float pitch = 1600.0f + 2400.0f * rng.uniform();
                const float F[] = {pitch, pitch * 2.76f, pitch * 5.4f}, Q[] = {45, 40, 35}, G[] = {1, .45f, .2f};
                v.res.set(3, F, Q, G, sr);
                float peak = rng.uniform();
                v.env[0].trigger(0.002f, 0.06f + 0.08f * rng.uniform(), sr,
                                 (0.15f + 0.85f * peak * peak) * (0.4f + 0.6f * settling));
            }
            float tick = v.res.process(rng.white() * v.env[0].next()) * 0.04f;  // quieter again (Dan)
            s = ((hum * 0.5f + v.f[0].low * 0.25f) * humLevel + tick) * loud;
            break;
        }
        case recipe::STRESS: {
            float amount = clampf(p[0], 0, 1);
            // A creak: a rasping tone gliding down through a tight resonance.
            if (v.state[0] <= 0 && rng.uniform() < (0.2f + 3.5f * amount) * dt) {
                v.state[0] = 0.25f + 0.7f * rng.uniform();
                v.state[3] = v.state[0];
                v.state[1] = 260.0f + 600.0f * rng.uniform();
                v.state[2] = v.state[1] * (0.55f + 0.2f * rng.uniform());
                v.env[0].trigger(0.03f, v.state[0] * 0.5f, sr);
            }
            float creak = 0;
            if (v.state[0] > 0) {
                float t = 1.0f - v.state[0] / v.state[3];
                float freq = lerpf(v.state[1], v.state[2], t);
                v.state[0] -= dt;
                if (control) v.f[0].set(freq * 2.2f, 9.0f, sr);
                v.f[0].process(v.osc[0].saw(freq, sr) * (0.6f + 0.4f * rng.white()));
                creak = v.f[0].band * v.env[0].next() * 0.8f;
            }
            // Near the limit, sharp tinks as metal gives.
            if (amount > 0.7f && rng.uniform() < (amount - 0.7f) * 6.0f * dt) {
                setMetal(v.res, 1.2f, sr);
                v.env[1].trigger(0.0005f, 0.004f, sr);
            }
            float tink = v.res.process(rng.white() * v.env[1].next()) * 0.3f;
            s = (creak + tink) * (0.4f + 0.6f * amount);
            break;
        }
        case recipe::FIRE: {
            float loud = clampf(p[0], 0, 1.5f);
            if (control) { v.f[0].set(2500.0f, 0.8f, sr); v.f[1].set(250.0f, 0.7f, sr); v.f[2].set(450.0f, 0.7f, sr); }
            v.f[0].process(v.crackle.next(rng, 60.0f, sr));
            v.f[1].process(v.brown.next(rng.white()));
            // The flames' own rush, flickering.
            v.state[0] += (rng.uniform() - v.state[0]) * (dt / 0.08f);
            v.f[2].process(v.pink.next(rng.white()));
            s = (v.f[0].band * 6.0f + v.f[1].low * 0.15f + v.f[2].band * 3.0f * (0.5f + v.state[0])) * loud;
            break;
        }
        case recipe::ROVER: {
            float load = clampf(p[0], 0, 1), speed = clampf(p[1], 0, 40), grit = clampf(p[2], 0, 1), skid = clampf(p[3], 0, 1);
            // What the ground is made of: 0 hard (rock, gravel - sharp
            // crunches) to 1 soft (sand, snow - a duller hiss and crush).
            float soft = clampf(p[4], 0, 1);
            float f = (80.0f + 45.0f * speed) * pf;
            // The motors' hum, muffled: its tone ran six times the shaft rate,
            // up to nearly 5 kHz at speed - the loudest, shrillest thing in the
            // game (measured 10 dB over a rocket at full power).
            if (control) {
                v.f[0].set((400.0f + 60.0f * speed) * (1.0f - 0.35f * soft) * pf, 0.8f, sr);
                v.f[1].set(1800.0f * pf, 3.0f, sr);
                v.f[2].set(1200.0f * pf, 0.7f, sr);
                // The ground under the tyres: gravel's crunch lower on soft ground.
                v.f[3].set(lerpf(1400.0f, 600.0f, soft), 0.9f, sr);
            }
            v.f[2].process(v.osc[0].sine(f * 1.5f, sr) * 0.15f + v.osc[1].saw(f, sr) * 0.08f);
            float whine = v.f[2].low;
            float rolling = std::min(1.0f, speed / 8.0f);
            v.f[0].process(v.pink.next(rng.white()));
            v.f[1].process(rng.white());
            float gravel = v.crackle.next(rng, (20.0f + 30.0f * speed) * (1.0f + 1.5f * soft), sr) * grit * std::min(1.0f, speed / 5.0f);
            v.f[3].process(gravel);
            s = whine * (0.3f + 0.7f * load) * std::min(1.0f, speed / 2.0f + load)
                + v.f[0].low * (0.8f + 0.4f * soft) * rolling + v.f[3].band * 1.4f + v.f[1].band * 0.3f * skid;
            break;
        }
        case recipe::OUTBOARD: {
            // A low, uneven burble - each stroke a little stronger or weaker
            // than the last, never a buzz - and the propeller churning the
            // water under it. All of it well down: the old one rang a saw
            // through a narrow band at 1.2 kHz, and on a phone that whine
            // was all there was to hear (Dan: too high pitched).
            float out = clampf(p[0], 0, 1), rev = clampf(p[1], 0, 1);
            float f = (16.0f + 38.0f * rev) * pf;
            if (control) {
                float corner = (320.0f + 220.0f * rev) * pf;
                v.f[0].set(corner, 0.7f, sr);
                v.f[3].set(corner, 0.7f, sr);
                v.f[2].set((350.0f + 250.0f * rev) * pf, 0.6f, sr);
                // The deepest thump off: felt more than heard, and on good
                // speakers it would sit far over everything else.
                v.f[1].set(110.0f, 0.7f, sr);
            }
            float pulse = v.osc[0].pulse(f, 0.4f, sr);
            if (v.osc[0].phase < v.state[1]) v.state[0] = 0.55f + 0.45f * rng.uniform();
            v.state[1] = v.osc[0].phase;
            v.f[0].process(pulse * v.state[0]);
            v.f[3].process(v.f[0].low);
            v.f[2].process(v.brown.next(rng.white()));
            v.f[1].process(v.f[3].low * 0.9f + v.f[2].low * (0.3f + 0.5f * rev));
            s = v.f[1].high * out;
            break;
        }
        case recipe::SURF: {
            float loud = clampf(p[0], 0, 1.5f);
            v.state[0] += dt / 7.5f;
            v.state[0] -= std::floor(v.state[0]);
            float wave = std::pow(std::max(0.0f, std::sin(kTwoPi * v.state[0])), 3.0f);
            // Waves breaking on the shore: a slow swell and wash, low and
            // soft - it goes on as long as you stay by the sea.
            if (control) { v.f[0].set(250.0f + 500.0f * wave, 0.6f, sr); v.f[1].set(700.0f + 400.0f * wave, 0.7f, sr); }
            v.f[0].process(v.brown.next(rng.white()) * 0.6f + v.pink.next(rng.white()) * 0.4f);
            v.f[1].process(v.pink2.next(rng.white()));
            s = (v.f[0].low * (0.35f + wave) + v.f[1].low * 0.25f * wave * wave) * loud * 1.5f;
            break;
        }
        case recipe::SEA: {
            // The open sea: each swell's wash rising and falling, every one
            // its own length and strength - never a beat - low and soft; the
            // hiss of breaking crests, kept dark, when it is rough; and in a
            // storm a deep roll under it all.
            float loud = clampf(p[0], 0, 1.5f), rough = clampf(p[1], 0, 1), storm = clampf(p[2], 0, 1);
            if (v.state[1] <= 0) { v.state[1] = 5.0f + 4.0f * rng.uniform(); v.state[2] = 0.5f + 0.5f * rng.uniform(); }
            v.state[0] += dt / v.state[1];
            if (v.state[0] >= 1.0f) {
                v.state[0] -= 1.0f;
                v.state[1] = (5.0f - 1.5f * storm) + 4.0f * rng.uniform();
                v.state[2] = 0.5f + 0.5f * rng.uniform();
            }
            float swell = 0.5f - 0.5f * std::cos(kTwoPi * v.state[0]);
            float wash = swell * swell * v.state[2];
            if (control) {
                v.f[0].set(160.0f + 360.0f * wash + 180.0f * rough, 0.6f, sr);
                v.f[1].set(450.0f + 450.0f * rough, 0.7f, sr);
                v.f[2].set(85.0f, 0.7f, sr);
            }
            v.f[0].process(v.brown.next(rng.white()) * 0.7f + v.pink.next(rng.white()) * 0.3f);
            v.f[1].process(v.pink2.next(rng.white()));
            v.f[2].process(v.f[0].low);
            s = (v.f[0].low * (0.35f + 0.8f * wash) + v.f[1].low * 0.3f * rough * wash + v.f[2].low * 1.4f * storm) * loud;
            break;
        }
        case recipe::RCS: {
            // Thrusters: soft chuffs of gas, each a little different, coming
            // faster the harder they fire - never a steady hiss, never a beat.
            // state[0] is the wait to the next chuff; state[1] its pitch.
            float amount = clampf(p[0], 0, 1);
            v.state[0] -= dt;
            if (v.state[0] <= 0) {
                float rate = 3.0f + 9.0f * amount;  // chuffs a second
                v.state[0] = (0.35f + 0.65f * rng.uniform()) * 2.0f / rate;
                v.state[1] = 0.8f + 0.45f * rng.uniform();
                float peak = 0.5f + 0.5f * rng.uniform();
                v.env[0].trigger(0.008f, 0.06f + 0.08f * rng.uniform(), sr, peak * (0.4f + 0.6f * amount));
            }
            if (control) {
                v.f[0].set(850.0f * v.state[1] * pf, 1.0f, sr);
                v.f[1].set(900.0f * pf, 0.6f, sr);
                v.f[2].set(1500.0f * pf, 0.6f, sr);
            }
            float w = v.pink.next(rng.white());
            v.f[0].process(w * v.env[0].next());
            v.f[1].process(w);
            // Rounded off above: soft gas, not a hiss.
            v.f[2].process(v.f[0].band * 2.2f + v.f[1].low * 0.08f * amount);
            s = v.f[2].low;
            break;
        }

        // --- one-shots -----------------------------------------------------
        case recipe::IMPACT: {
            int m = static_cast<int>(p[1] + 0.5f);
            float ex = rng.white() * v.env[0].next();
            float ring = v.res.process(ex) * 3.0f;
            v.state[0] = 60.0f + (v.state[0] - 60.0f) * 0.9996f;
            float thump = v.osc[0].sine(v.state[0], sr) * v.env[1].next() * (m == material::METAL ? 0.5f : 1.0f);
            // The body of the blow: a short burst of the ground itself.
            v.f[0].process(rng.white() * v.env[1].value);
            float body = v.f[0].low * (m == material::METAL ? 0.3f : 5.0f);
            s = (ring + thump + body) * v.state[1] * materialGain(m);
            break;
        }
        case recipe::CRUNCH: {
            v.state[0] *= 0.99998f;  // the metal buckles, and its note sinks
            if (control) setMetal(v.res, 0.8f * v.state[0], sr);
            float ex = (rng.white() * 0.5f + v.crackle.next(rng, 300.0f, sr)) * v.env[0].next();
            v.f[0].process(ex);
            s = (v.res.process(ex) * 2.5f + v.f[0].low * 0.8f) * v.state[1];
            break;
        }
        case recipe::TEAR: {
            v.state[0] *= std::pow(600.0f / 3200.0f, dt / 0.5f);
            if (v.state[0] < 600.0f) v.state[0] = 600.0f;
            if (control) v.f[0].set(v.state[0], 6.0f, sr);
            v.f[0].process(v.pink.next(rng.white()) + v.osc[0].saw(v.state[0] / 8.0f, sr) * 0.3f);
            float rip = v.f[0].band * v.env[0].next() * 2.0f;
            float clang = v.res.process(rng.white() * v.env[1].next()) * 2.0f;
            s = (rip + clang) * v.state[1];
            break;
        }
        case recipe::EXPLOSION: {
            float w = rng.white();
            v.f[0].process(w);
            float crack = v.f[0].high * v.env[0].next() * 1.5f;
            v.f[1].process(v.brown.next(rng.white()));
            float boom = v.f[1].low * v.env[1].next() * 2.5f;
            float sub = v.osc[0].sine(45.0f, sr) * v.env[1].value * 0.6f;
            // The blast itself, in the middle of the range where it carries.
            v.f[3].process(v.pink.next(rng.white()));
            float blast = v.f[3].band * v.env[1].value * v.env[1].value * 14.0f;
            v.f[2].process(v.crackle.next(rng, 90.0f, sr));
            float tail = v.f[2].band * v.env[2].next() * 1.0f;
            s = (crack + boom + sub + blast + tail) * v.state[1];
            break;
        }
        case recipe::SLAP: {
            v.f[0].process(v.pink.next(rng.white()) * v.env[0].next());
            float slap = v.f[0].low * 2.0f;
            float thump = v.osc[0].sine(v.state[0], sr) * v.env[1].next() * 0.8f;
            s = (slap + thump) * v.state[1];
            break;
        }
        case recipe::SPLASH: {
            v.f[0].process(rng.white() * v.env[0].next());
            float burst = v.f[0].band * 1.5f;
            // Bubbles: short rising notes, thinning out.
            if (rng.uniform() < 40.0f * p[0] * std::exp(-v.age * 2.0f) * dt) {
                v.state[0] = 400.0f + 1100.0f * rng.uniform();
                v.env[1].trigger(0.001f, 0.03f, sr);
            }
            v.state[0] *= 1.00005f;
            float bubble = v.osc[0].sine(v.state[0], sr) * v.env[1].next() * 0.5f;
            s = (burst + bubble) * v.state[1];
            break;
        }
        case recipe::STAGE: {
            v.f[0].process(rng.white());
            float crack = v.f[0].high * v.env[0].next() * 1.2f;
            float clunk = v.res.process(rng.white() * v.env[1].next()) * 2.5f;
            s = crack + clunk;
            break;
        }
        case recipe::THUNDER: {
            v.f[0].process(rng.white());
            float crack = v.f[0].high * v.env[0].next() * 2.0f;
            v.state[2] -= dt;
            if (v.state[2] <= 0) { v.state[1] = 0.4f + 0.6f * rng.uniform(); v.state[2] = 0.15f + 0.4f * rng.uniform(); }
            v.state[0] += (v.state[1] - v.state[0]) * (dt / 0.15f);
            v.f[1].process(v.brown.next(rng.white()));
            v.f[2].process(v.pink.next(rng.white()));
            float e = v.env[1].next() * v.state[0];
            s = crack + v.f[1].low * e * 2.0f + v.f[2].band * e * 7.0f;
            break;
        }
        case recipe::CLICK: {
            float f = p[0] > 0.5f ? 900.0f : 1300.0f;
            float e = v.env[0].next();
            s = (v.osc[0].sine(f, sr) * 0.35f + rng.white() * 0.05f) * e;
            break;
        }
        case recipe::CAUTION: {
            float a = v.age;
            float f = a < 0.14f ? 880.0f : 660.0f;
            float on = (a < 0.12f || (a > 0.16f && a < 0.28f)) ? 1.0f : 0.0f;
            v.state[0] += (on - v.state[0]) * (dt / 0.004f);
            s = v.osc[0].sine(f, sr) * v.state[0] * 0.25f;
            break;
        }
        case recipe::IGNITION: {
            if (v.age < 0.3f) v.state[0] = 150.0f + 1350.0f * (v.age / 0.3f);
            if (control) v.f[0].set(v.state[0], 0.8f, sr);
            float w = rng.white();
            v.f[0].process(v.brown.next(w) + v.pink.next(w) * 0.5f);
            s = v.f[0].low * v.env[0].next() * 1.5f * (0.6f + 0.6f * clampf(p[0], 0, 1));
            break;
        }
        case recipe::CHUTE: {
            if (v.age < 0.35f) v.state[0] = 300.0f + 900.0f * (v.age / 0.35f);
            if (control) v.f[0].set(v.state[0], 1.0f, sr);
            v.f[0].process(v.pink.next(rng.white()));
            float whoosh = v.f[0].band * v.env[0].next() * 1.2f;
            if (v.age >= 0.35f && v.state[1] == 0) { v.state[1] = 1; v.env[1].trigger(0.0005f, 0.05f, sr); }
            float snap = rng.white() * v.env[1].next() * 0.6f;
            s = whoosh + snap;
            break;
        }
        case recipe::CLUNK: {
            float thud = v.osc[0].sine(120.0f, sr) * v.env[1].next();
            s = v.res.process(rng.white() * v.env[0].next()) * 2.5f + thud * 0.6f;
            s *= 0.5f + 0.5f * clampf(p[0], 0, 1);
            break;
        }
        default:
            break;
    }

    // Through the hull: only what the structure carries - low, close, dull.
    if (v.flags & flag::HULL) {
        if (control) v.f[4].set(220.0f, 2.5f, sr);
        float carried = v.hullLow.next(s);
        v.f[4].process(carried);
        s = (carried + v.f[4].band * 1.2f) * 0.7f;
    }
    // Distance and air: the far away loses its top.
    if (control) v.distance.set(p[P_LOWPASS] > 0 ? p[P_LOWPASS] : 20000.0f, sr);
    s = v.dc.next(v.distance.next(s));

    bool isContinuous = continuous(v.recipe);
    float fade = v.fade.next(!isContinuous || v.held ? 1.0f : 0.0f);
    s *= fade * trim(v.recipe) * (p[P_GAIN] > 0 ? p[P_GAIN] : 1.0f);
    float lg, rg;
    panGains((v.flags & flag::HULL) ? 0.0f : p[P_PAN], lg, rg);
    left = s * lg;
    right = s * rg;
    v.loudness = v.loudness * 0.999f + std::fabs(s) * 0.001f;
    v.age += dt;

    if (isContinuous ? (!v.held && v.fade.value < 1e-4f) : v.age > lifetime(v.recipe, v.target)) v.active = false;
}

float Synth::reverb(float in, int c) {
    float out = 0;
    for (int i = 0; i < 4; ++i) {
        std::vector<float>& buf = comb_[c][i];
        int& idx = combIndex_[c][i];
        float y = buf[idx];
        combFilter_[c][i] = y * 0.8f + combFilter_[c][i] * 0.2f;  // damping
        buf[idx] = in + combFilter_[c][i] * 0.78f;               // room size
        idx = (idx + 1) % static_cast<int>(buf.size());
        out += y;
    }
    for (int i = 0; i < 2; ++i) {
        std::vector<float>& buf = allpass_[c][i];
        int& idx = allpassIndex_[c][i];
        float b = buf[idx];
        buf[idx] = out + b * 0.5f;
        out = b - out;
        idx = (idx + 1) % static_cast<int>(buf.size());
    }
    return out * 0.25f;
}

void Synth::render(float* out, int frames) {
    takeScene();
    takeEvents();
    float busTargets[bus::COUNT];
    for (int b = 0; b < bus::COUNT; ++b) busTargets[b] = busTarget_[b].load(std::memory_order_relaxed);
    const float roomTarget = roomTarget_.load(std::memory_order_relaxed);

    for (int n = 0; n < frames; ++n) {
        float busL[bus::COUNT] = {}, busR[bus::COUNT] = {};
        for (int i = 0; i < voiceBudget_; ++i) {
            Voice& v = voices_[i];
            if (!v.active) continue;
            float l, r;
            renderVoice(v, l, r);
            int b = busOf(v.recipe);
            busL[b] += l;
            busR[b] += r;
        }
        float l = 0, r = 0;
        for (int b = 0; b < bus::COUNT; ++b) {
            float g = bus_[b].next(busTargets[b]);
            l += busL[b] * g;
            r += busR[b] * g;
        }
        float room = room_.next(roomTarget);
        float mono = (l + r) * 0.5f;
        l += reverb(mono, 0) * room;
        r += reverb(mono, 1) * room;

        // Limiter: instant down, slow back up, so a blast never clips.
        if (!limiting) { out[2 * n] = l; out[2 * n + 1] = r; continue; }
        float peak = std::max(std::fabs(l), std::fabs(r));
        if (peak * limiterGain_ > 0.9f) limiterGain_ = 0.9f / peak;
        else limiterGain_ += (1.0f - limiterGain_) * 0.0002f;
        out[2 * n] = softClip(l * limiterGain_);
        out[2 * n + 1] = softClip(r * limiterGain_);
    }
}

}  // namespace apogee
