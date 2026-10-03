use std::collections::BTreeSet;

use crate::conditions::RAIN_THRESHOLD_MM_H;
use crate::scene::{Precipitation, Scene, Sky, TimeOfDay, Wind};
use crate::Conditions;

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum Top {
        TankTop => "tank-top",
        TShirt => "t-shirt",
        LongSleeve => "long-sleeve",
        Sweater => "sweater",
    }
}

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum Bottom {
        Shorts => "shorts",
        Trousers => "trousers",
    }
}

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum Footwear {
        Sandals => "sandals",
        Sneakers => "sneakers",
        Boots => "boots",
        RainBoots => "rain-boots",
    }
}

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum Outerwear {
        LightJacket => "light-jacket",
        Raincoat => "raincoat",
        Coat => "coat",
        PufferCoat => "puffer-coat",
    }
}

slug_enum! {
    /// Declaration order is draw order (back to front).
    #[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
    pub enum Accessory {
        Scarf => "scarf",
        Gloves => "gloves",
        Sunglasses => "sunglasses",
        Beanie => "beanie",
        SunHat => "sun-hat",
        ClosedUmbrella => "umbrella-closed",
        Umbrella => "umbrella-open",
    }
}

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum Expression {
        Happy => "happy",
        Sleepy => "sleepy",
        Shivering => "shivering",
        Sweaty => "sweaty",
        Soggy => "soggy",
        Windswept => "windswept",
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Outfit {
    pub top: Top,
    pub bottom: Bottom,
    pub footwear: Footwear,
    pub outerwear: Option<Outerwear>,
    /// Sorted in draw order, no duplicates.
    pub accessories: Vec<Accessory>,
    pub expression: Expression,
}

impl Outfit {
    pub fn has(&self, accessory: Accessory) -> bool {
        self.accessories.contains(&accessory)
    }
}

/// Rain heavier than this (mm/h) calls for rain boots.
const RAIN_BOOTS_MM_H: f32 = 2.5;

/// Dresses the buddy for the "feels like" temperature, rain in the next hour,
/// wind (umbrellas don't survive Dutch gusts) and sun.
pub fn dress(c: &Conditions, scene: &Scene) -> Outfit {
    use Accessory::*;

    let feels = c.apparent_temperature_c;
    let (top, bottom, mut footwear, mut outerwear) = match feels {
        f if f >= 25.0 => (Top::TankTop, Bottom::Shorts, Footwear::Sandals, None),
        f if f >= 20.0 => (Top::TShirt, Bottom::Shorts, Footwear::Sneakers, None),
        f if f >= 15.0 => (Top::LongSleeve, Bottom::Trousers, Footwear::Sneakers, None),
        f if f >= 10.0 => (
            Top::Sweater,
            Bottom::Trousers,
            Footwear::Sneakers,
            Some(Outerwear::LightJacket),
        ),
        f if f >= 3.0 => (
            Top::Sweater,
            Bottom::Trousers,
            Footwear::Boots,
            Some(Outerwear::Coat),
        ),
        _ => (
            Top::Sweater,
            Bottom::Trousers,
            Footwear::Boots,
            Some(Outerwear::PufferCoat),
        ),
    };

    let mut acc = BTreeSet::new();
    if feels < 8.0 {
        acc.insert(Scarf);
    }
    if feels < 3.0 {
        acc.extend([Beanie, Gloves]);
    }

    let wet_now = scene.precipitation.is_wet();
    let rain_soon = c.max_rain_within(60) >= RAIN_THRESHOLD_MM_H;
    let snowing = scene.precipitation == Precipitation::Snow;
    let calm = scene.wind == Wind::Calm;

    if wet_now || (rain_soon && !snowing) {
        if calm {
            acc.insert(if wet_now { Umbrella } else { ClosedUmbrella });
        } else if outerwear == Some(Outerwear::PufferCoat) {
            acc.insert(Beanie);
        } else {
            outerwear = Some(Outerwear::Raincoat);
        }
        let heavy = matches!(
            scene.precipitation,
            Precipitation::HeavyRain | Precipitation::Hail
        ) || c.max_rain_within(30) >= RAIN_BOOTS_MM_H;
        if footwear != Footwear::Sandals && (heavy || (wet_now && !calm)) {
            footwear = Footwear::RainBoots;
        }
    }

    if snowing {
        footwear = Footwear::Boots;
        acc.extend([Scarf, Beanie, Gloves]);
    }

    let sunny = scene.time_of_day == TimeOfDay::Day
        && matches!(scene.sky, Sky::Clear | Sky::PartlyCloudy)
        && scene.precipitation == Precipitation::None;
    if sunny {
        // Without a UV value, fall back to temperature as a rough proxy.
        let (glasses, hat) = match c.uv_index {
            Some(uv) => (uv >= 3.0, uv >= 6.0),
            None => (c.temperature_c >= 15.0, feels >= 25.0),
        };
        if glasses {
            acc.insert(Sunglasses);
        }
        if hat && scene.wind != Wind::Stormy && !acc.contains(&Beanie) {
            acc.insert(SunHat);
        }
    }

    let expression = if scene.wind == Wind::Stormy {
        Expression::Windswept
    } else if wet_now && !acc.contains(&Umbrella) {
        Expression::Soggy
    } else if feels < 0.0 {
        Expression::Shivering
    } else if feels >= 28.0 {
        Expression::Sweaty
    } else if scene.time_of_day == TimeOfDay::Night {
        Expression::Sleepy
    } else {
        Expression::Happy
    };

    Outfit {
        top,
        bottom,
        footwear,
        outerwear,
        accessories: acc.into_iter().collect(),
        expression,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::RainStep;

    fn conditions(code: u8, feels: f32, gusts: f32) -> Conditions {
        Conditions {
            temperature_c: feels,
            apparent_temperature_c: feels,
            wind_speed_kmh: gusts / 2.0,
            wind_gusts_kmh: gusts,
            uv_index: None,
            weather_code: code,
            is_day: true,
            precipitation_mm: 0.0,
            rain_nowcast: Vec::new(),
        }
    }

    fn outfit(c: &Conditions) -> Outfit {
        dress(c, &Scene::from_conditions(c))
    }

    #[test]
    fn hot_sunny_day() {
        let mut c = conditions(0, 29.0, 10.0);
        c.uv_index = Some(7.0);
        let o = outfit(&c);
        assert_eq!(
            (o.top, o.bottom, o.footwear, o.outerwear),
            (Top::TankTop, Bottom::Shorts, Footwear::Sandals, None)
        );
        assert_eq!(
            o.accessories,
            vec![Accessory::Sunglasses, Accessory::SunHat]
        );
        assert_eq!(o.expression, Expression::Sweaty);
    }

    #[test]
    fn freezing_snow() {
        let o = outfit(&conditions(73, -4.0, 20.0));
        assert_eq!(o.outerwear, Some(Outerwear::PufferCoat));
        assert_eq!(o.footwear, Footwear::Boots);
        assert_eq!(
            o.accessories,
            vec![Accessory::Scarf, Accessory::Gloves, Accessory::Beanie]
        );
        assert_eq!(o.expression, Expression::Shivering);
    }

    #[test]
    fn calm_rain_gets_an_umbrella() {
        let o = outfit(&conditions(63, 12.0, 20.0));
        assert_eq!(o.outerwear, Some(Outerwear::LightJacket));
        assert!(o.has(Accessory::Umbrella));
        assert_eq!(o.expression, Expression::Happy);
    }

    #[test]
    fn windy_rain_gets_a_raincoat_instead() {
        let o = outfit(&conditions(63, 12.0, 50.0));
        assert_eq!(o.outerwear, Some(Outerwear::Raincoat));
        assert_eq!(o.footwear, Footwear::RainBoots);
        assert!(!o.has(Accessory::Umbrella));
        assert_eq!(o.expression, Expression::Soggy);
    }

    #[test]
    fn rain_coming_soon_means_closed_umbrella() {
        let mut c = conditions(2, 17.0, 10.0);
        c.rain_nowcast = (0..12)
            .map(|i| RainStep {
                hour: 14,
                minute: i * 5,
                mm_per_hour: if i >= 6 { 1.0 } else { 0.0 },
            })
            .collect();
        let o = outfit(&c);
        assert!(o.has(Accessory::ClosedUmbrella));
        assert_eq!(o.footwear, Footwear::Sneakers);
    }

    #[test]
    fn night_is_sleepy_and_has_no_sunglasses() {
        let mut c = conditions(0, 18.0, 10.0);
        c.is_day = false;
        let o = outfit(&c);
        assert!(!o.has(Accessory::Sunglasses));
        assert_eq!(o.expression, Expression::Sleepy);
    }
}
