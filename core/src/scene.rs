use crate::conditions::RAIN_THRESHOLD_MM_H;
use crate::Conditions;

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum Sky {
        Clear => "clear",
        PartlyCloudy => "partly-cloudy",
        Overcast => "overcast",
        Fog => "fog",
        Thunderstorm => "thunderstorm",
    }
}

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum Precipitation {
        None => "none",
        Drizzle => "drizzle",
        Rain => "rain",
        HeavyRain => "heavy-rain",
        Snow => "snow",
        Hail => "hail",
    }
}

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum TimeOfDay {
        Day => "day",
        Night => "night",
    }
}

slug_enum! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub enum Wind {
        Calm => "calm",
        Breezy => "breezy",
        Stormy => "stormy",
    }
}

/// What the world around the buddy looks like.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Scene {
    pub sky: Sky,
    pub precipitation: Precipitation,
    pub time_of_day: TimeOfDay,
    pub wind: Wind,
}

impl Precipitation {
    pub fn is_wet(self) -> bool {
        matches!(
            self,
            Self::Drizzle | Self::Rain | Self::HeavyRain | Self::Hail
        )
    }
}

impl Scene {
    pub fn from_conditions(c: &Conditions) -> Self {
        Self {
            sky: sky_from_code(c.weather_code),
            precipitation: precipitation(c),
            time_of_day: if c.is_day {
                TimeOfDay::Day
            } else {
                TimeOfDay::Night
            },
            wind: match c.wind_gusts_kmh.max(c.wind_speed_kmh) {
                g if g < 35.0 => Wind::Calm,
                g if g < 62.0 => Wind::Breezy,
                _ => Wind::Stormy,
            },
        }
    }
}

/// Sky for a WMO weather code. Showers get sunny spells, steady rain is grey.
fn sky_from_code(code: u8) -> Sky {
    match code {
        0 | 1 => Sky::Clear,
        2 | 80..=86 => Sky::PartlyCloudy,
        45 | 48 => Sky::Fog,
        95..=99 => Sky::Thunderstorm,
        _ => Sky::Overcast,
    }
}

fn precipitation_from_code(code: u8) -> Precipitation {
    match code {
        51..=57 => Precipitation::Drizzle,
        65 | 82 => Precipitation::HeavyRain,
        61..=67 | 80 | 81 | 95 => Precipitation::Rain,
        71..=77 | 85 | 86 => Precipitation::Snow,
        96 | 99 => Precipitation::Hail,
        _ => Precipitation::None,
    }
}

/// The radar nowcast is more accurate for "is it raining right now" than the
/// model, so it wins when present; the model code still decides snow vs. hail.
fn precipitation(c: &Conditions) -> Precipitation {
    let from_code = precipitation_from_code(c.weather_code);
    let Some(now) = c.rain_now_mm_h() else {
        if from_code == Precipitation::None && c.precipitation_mm >= RAIN_THRESHOLD_MM_H {
            return Precipitation::Rain;
        }
        return from_code;
    };
    match from_code {
        _ if now < RAIN_THRESHOLD_MM_H => Precipitation::None,
        Precipitation::Snow | Precipitation::Hail => from_code,
        _ if c.temperature_c <= 1.0 => Precipitation::Snow,
        _ if now < 0.5 => Precipitation::Drizzle,
        _ if now < 4.0 => Precipitation::Rain,
        _ => Precipitation::HeavyRain,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::RainStep;

    fn conditions(code: u8, temp: f32, gusts: f32) -> Conditions {
        Conditions {
            temperature_c: temp,
            apparent_temperature_c: temp,
            wind_speed_kmh: gusts / 2.0,
            wind_gusts_kmh: gusts,
            uv_index: None,
            weather_code: code,
            is_day: true,
            precipitation_mm: 0.0,
            rain_nowcast: Vec::new(),
        }
    }

    fn nowcast(mm: &[f32]) -> Vec<RainStep> {
        mm.iter()
            .enumerate()
            .map(|(i, &mm_per_hour)| RainStep {
                hour: 14,
                minute: (i * 5) as u8,
                mm_per_hour,
            })
            .collect()
    }

    #[test]
    fn model_code_without_radar() {
        let s = Scene::from_conditions(&conditions(63, 12.0, 20.0));
        assert_eq!(
            (s.sky, s.precipitation, s.wind),
            (Sky::Overcast, Precipitation::Rain, Wind::Calm)
        );
        let s = Scene::from_conditions(&conditions(99, 20.0, 80.0));
        assert_eq!(
            (s.sky, s.precipitation, s.wind),
            (Sky::Thunderstorm, Precipitation::Hail, Wind::Stormy)
        );
    }

    #[test]
    fn radar_overrides_model() {
        let mut c = conditions(3, 12.0, 20.0);
        c.rain_nowcast = nowcast(&[5.0, 1.0]);
        assert_eq!(
            Scene::from_conditions(&c).precipitation,
            Precipitation::HeavyRain
        );

        let mut c = conditions(63, 12.0, 20.0);
        c.rain_nowcast = nowcast(&[0.0, 3.0]);
        assert_eq!(
            Scene::from_conditions(&c).precipitation,
            Precipitation::None
        );

        let mut c = conditions(3, 0.5, 20.0);
        c.rain_nowcast = nowcast(&[1.0]);
        assert_eq!(
            Scene::from_conditions(&c).precipitation,
            Precipitation::Snow
        );
    }
}
