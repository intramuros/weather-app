//! Art styles and the layer stack that turns a scene + outfit into a picture.
//!
//! Every style ships the same set of transparent, full-canvas PNG layers at
//! `<style>/<category>/<slug>.png`, so switching style only swaps the folder.

use crate::outfit::{Accessory, Bottom, Expression, Footwear, Outerwear, Outfit, Top};
use crate::scene::{Precipitation, Scene, Sky, TimeOfDay, Wind};

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum Style {
        PixelArt => "pixel-art",
        UkiyoE => "ukiyo-e",
        DelftsBlauw => "delfts-blauw",
    }
}

/// One image to draw, bottom-most first.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Layer {
    pub path: String,
}

impl Style {
    pub fn display_name(self) -> &'static str {
        match self {
            Style::PixelArt => "Pixel art",
            Style::UkiyoE => "Ukiyo-e",
            Style::DelftsBlauw => "Delfts Blauw",
        }
    }

    fn layer(self, category: &str, slug: &str) -> Layer {
        Layer {
            path: format!("{}/{category}/{slug}.png", self.slug()),
        }
    }

    fn background(self, sky: Sky, time: TimeOfDay) -> Layer {
        self.layer("background", &format!("{}-{}", sky.slug(), time.slug()))
    }

    pub fn layers(self, scene: &Scene, outfit: &Outfit) -> Vec<Layer> {
        let precipitation = (scene.precipitation != Precipitation::None)
            .then(|| self.layer("fx", scene.precipitation.slug()));
        // Under an open umbrella the rain falls behind the buddy.
        let (rain_behind, rain_in_front) = if outfit.has(Accessory::Umbrella) {
            (precipitation, None)
        } else {
            (None, precipitation)
        };

        let mut layers = vec![self.background(scene.sky, scene.time_of_day)];
        layers.extend(rain_behind);
        layers.extend([
            self.layer("body", "base"),
            self.layer("face", outfit.expression.slug()),
            self.layer("bottom", outfit.bottom.slug()),
            self.layer("footwear", outfit.footwear.slug()),
            self.layer("top", outfit.top.slug()),
        ]);
        layers.extend(outfit.outerwear.map(|o| self.layer("outerwear", o.slug())));
        layers.extend(
            outfit
                .accessories
                .iter()
                .map(|a| self.layer("accessory", a.slug())),
        );
        layers.extend(rain_in_front);
        if scene.wind != Wind::Calm {
            layers.push(self.layer("fx", &format!("wind-{}", scene.wind.slug())));
        }
        layers
    }

    /// Every asset path this style's asset pack must contain.
    pub fn required_assets(self) -> Vec<String> {
        let mut paths: Vec<Layer> = Sky::ALL
            .iter()
            .flat_map(|&sky| TimeOfDay::ALL.iter().map(move |&time| (sky, time)))
            .map(|(sky, time)| self.background(sky, time))
            .collect();
        paths.push(self.layer("body", "base"));
        paths.extend(Expression::ALL.iter().map(|e| self.layer("face", e.slug())));
        paths.extend(Bottom::ALL.iter().map(|b| self.layer("bottom", b.slug())));
        paths.extend(
            Footwear::ALL
                .iter()
                .map(|f| self.layer("footwear", f.slug())),
        );
        paths.extend(Top::ALL.iter().map(|t| self.layer("top", t.slug())));
        paths.extend(
            Outerwear::ALL
                .iter()
                .map(|o| self.layer("outerwear", o.slug())),
        );
        paths.extend(
            Accessory::ALL
                .iter()
                .map(|a| self.layer("accessory", a.slug())),
        );
        paths.extend(
            Precipitation::ALL
                .iter()
                .filter(|&&p| p != Precipitation::None)
                .map(|p| self.layer("fx", p.slug())),
        );
        paths.extend(
            Wind::ALL
                .iter()
                .filter(|&&w| w != Wind::Calm)
                .map(|w| self.layer("fx", &format!("wind-{}", w.slug()))),
        );
        paths.into_iter().map(|l| l.path).collect()
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashSet;

    use super::*;
    use crate::{plan, Conditions, RainStep};

    /// A grid of conditions covering every code family, temperature band,
    /// wind class, day/night and radar state.
    fn condition_grid() -> Vec<Conditions> {
        let codes = [0, 1, 2, 3, 45, 51, 61, 63, 65, 71, 80, 82, 85, 95, 96, 99];
        let temps = [-5.0, 0.5, 5.0, 12.0, 17.0, 22.0, 29.0];
        let gusts = [10.0, 45.0, 80.0];
        let uvs = [None, Some(4.0), Some(8.0)];
        let rain_in_half_an_hour: Vec<f32> =
            (0..24).map(|i| if i >= 6 { 1.0 } else { 0.0 }).collect();
        let nowcasts: [&[f32]; 5] = [
            &[],
            &[0.0; 24],
            &rain_in_half_an_hour,
            &[0.3; 24],
            &[6.0; 24],
        ];
        let mut grid = Vec::new();
        for &code in &codes {
            for &temp in &temps {
                for &gust in &gusts {
                    for &uv in &uvs {
                        for &nowcast in &nowcasts {
                            for is_day in [true, false] {
                                grid.push(Conditions {
                                    temperature_c: temp,
                                    apparent_temperature_c: temp,
                                    wind_speed_kmh: gust / 2.0,
                                    wind_gusts_kmh: gust,
                                    uv_index: uv,
                                    weather_code: code,
                                    is_day,
                                    precipitation_mm: 0.0,
                                    rain_nowcast: nowcast
                                        .iter()
                                        .enumerate()
                                        .map(|(i, &mm_per_hour)| RainStep {
                                            hour: 14 + (i / 12) as u8,
                                            minute: (i % 12 * 5) as u8,
                                            mm_per_hour,
                                        })
                                        .collect(),
                                });
                            }
                        }
                    }
                }
            }
        }
        grid
    }

    #[test]
    fn every_drawn_layer_is_a_required_asset_and_every_asset_is_reachable() {
        for style in Style::ALL.iter().copied() {
            let required: HashSet<String> = style.required_assets().into_iter().collect();
            let mut used = HashSet::new();
            for c in condition_grid() {
                for layer in plan(&c, style).layers {
                    assert!(
                        required.contains(&layer.path),
                        "{} not in asset list",
                        layer.path
                    );
                    used.insert(layer.path);
                }
            }
            let mut unused: Vec<_> = required.difference(&used).collect();
            unused.sort();
            assert!(unused.is_empty(), "never drawn for {style:?}: {unused:?}");
        }
    }

    #[test]
    fn umbrella_puts_rain_behind_the_buddy() {
        let c = Conditions {
            temperature_c: 12.0,
            apparent_temperature_c: 12.0,
            wind_speed_kmh: 5.0,
            wind_gusts_kmh: 10.0,
            uv_index: None,
            weather_code: 63,
            is_day: true,
            precipitation_mm: 1.0,
            rain_nowcast: Vec::new(),
        };
        let paths: Vec<_> = plan(&c, Style::DelftsBlauw)
            .layers
            .into_iter()
            .map(|l| l.path)
            .collect();
        assert_eq!(
            paths,
            [
                "delfts-blauw/background/overcast-day.png",
                "delfts-blauw/fx/rain.png",
                "delfts-blauw/body/base.png",
                "delfts-blauw/face/happy.png",
                "delfts-blauw/bottom/trousers.png",
                "delfts-blauw/footwear/sneakers.png",
                "delfts-blauw/top/sweater.png",
                "delfts-blauw/outerwear/light-jacket.png",
                "delfts-blauw/accessory/umbrella-open.png",
            ]
        );
    }
}
