# Weather Buddy

A cute character dressed for the weather where you are, shown as an Android
widget, wallpaper or lock screen. You can pick the art style: **Pixel art**,
**Ukiyo-e** or **Delfts Blauw**.

Weather data comes from [Open-Meteo](https://open-meteo.com) (KNMI HARMONIE
model) and rain radar from [Buienradar](https://www.buienradar.nl).

## Layout

- `core/`: `weather-core`, the shared Rust logic. It turns API responses into
  conditions, then into a scene, an outfit and an ordered list of image layers.
- `docs/DESIGN.md`: decisions, dressing rules, asset pack spec and roadmap.

## Develop

```sh
cargo test
cargo clippy --all-targets
```
