/// Normalised weather at the user's location, independent of the data source.
#[derive(Debug, Clone, PartialEq)]
pub struct Conditions {
    pub temperature_c: f32,
    /// "Feels like" temperature; drives clothing choices.
    pub apparent_temperature_c: f32,
    pub wind_speed_kmh: f32,
    pub wind_gusts_kmh: f32,
    /// `None` when the model does not provide UV (e.g. KNMI HARMONIE).
    pub uv_index: Option<f32>,
    /// WMO weather interpretation code as used by Open-Meteo.
    pub weather_code: u8,
    pub is_day: bool,
    /// Precipitation over the preceding interval, in mm.
    pub precipitation_mm: f32,
    /// Radar-based rain forecast for the next ~2 hours (Buienradar), in time order.
    pub rain_nowcast: Vec<RainStep>,
}

/// One 5-minute step of the rain forecast.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct RainStep {
    pub hour: u8,
    pub minute: u8,
    pub mm_per_hour: f32,
}

/// Below this intensity we treat the rain forecast as dry.
pub const RAIN_THRESHOLD_MM_H: f32 = 0.1;

impl Conditions {
    /// Rain intensity right now according to the radar nowcast, if we have one.
    pub fn rain_now_mm_h(&self) -> Option<f32> {
        self.rain_nowcast.first().map(|s| s.mm_per_hour)
    }

    /// Strongest rain expected within the next `minutes` (5-minute steps).
    pub fn max_rain_within(&self, minutes: u32) -> f32 {
        let steps = (minutes / 5 + 1) as usize;
        self.rain_nowcast
            .iter()
            .take(steps)
            .map(|s| s.mm_per_hour)
            .fold(0.0, f32::max)
    }
}
