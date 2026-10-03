//! Open-Meteo forecast API, using the KNMI HARMONIE model for the Netherlands.
//!
//! Free without an API key for non-commercial use; see <https://open-meteo.com/en/terms>.

use serde::Deserialize;

use crate::{Conditions, Error};

const CURRENT_FIELDS: &str = "temperature_2m,apparent_temperature,is_day,precipitation,\
weather_code,wind_speed_10m,wind_gusts_10m,uv_index";

/// URL for the current conditions at a location.
pub fn current_url(latitude: f64, longitude: f64) -> String {
    format!(
        "https://api.open-meteo.com/v1/forecast?latitude={latitude:.4}&longitude={longitude:.4}\
         &current={CURRENT_FIELDS}&models=knmi_seamless&wind_speed_unit=kmh&timezone=auto"
    )
}

#[derive(Deserialize)]
struct Response {
    current: Current,
}

#[derive(Deserialize)]
struct Current {
    temperature_2m: Option<f32>,
    apparent_temperature: Option<f32>,
    is_day: Option<u8>,
    precipitation: Option<f32>,
    weather_code: Option<u8>,
    wind_speed_10m: Option<f32>,
    wind_gusts_10m: Option<f32>,
    uv_index: Option<f32>,
}

/// Parses an Open-Meteo `current` response. The rain nowcast is left empty;
/// fill it from [`crate::buienradar`].
pub fn parse_current(json: &str) -> Result<Conditions, Error> {
    let c = serde_json::from_str::<Response>(json)?.current;
    let temperature_c = c
        .temperature_2m
        .ok_or(Error::MissingField("temperature_2m"))?;
    let wind_speed_kmh = c.wind_speed_10m.unwrap_or(0.0);
    Ok(Conditions {
        temperature_c,
        apparent_temperature_c: c.apparent_temperature.unwrap_or(temperature_c),
        wind_speed_kmh,
        wind_gusts_kmh: c.wind_gusts_10m.unwrap_or(wind_speed_kmh),
        uv_index: c.uv_index,
        weather_code: c.weather_code.ok_or(Error::MissingField("weather_code"))?,
        is_day: c.is_day.ok_or(Error::MissingField("is_day"))? != 0,
        precipitation_mm: c.precipitation.unwrap_or(0.0),
        rain_nowcast: Vec::new(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_current_block() {
        let json = r#"{
            "latitude": 52.37, "longitude": 4.89,
            "current_units": {"temperature_2m": "°C"},
            "current": {
                "time": "2026-10-03T14:15", "interval": 900,
                "temperature_2m": 13.4, "apparent_temperature": 10.9, "is_day": 1,
                "precipitation": 0.3, "weather_code": 61,
                "wind_speed_10m": 24.1, "wind_gusts_10m": 48.2, "uv_index": null
            }
        }"#;
        let c = parse_current(json).unwrap();
        assert_eq!(c.temperature_c, 13.4);
        assert_eq!(c.apparent_temperature_c, 10.9);
        assert!(c.is_day);
        assert_eq!(c.weather_code, 61);
        assert_eq!(c.uv_index, None);
        assert!(c.rain_nowcast.is_empty());
    }

    #[test]
    fn missing_temperature_is_an_error() {
        let json = r#"{"current": {"weather_code": 0, "is_day": 1}}"#;
        assert!(matches!(
            parse_current(json),
            Err(Error::MissingField("temperature_2m"))
        ));
    }

    #[test]
    fn url_requests_knmi_model() {
        let url = current_url(52.0907, 5.1214);
        assert!(url.contains("latitude=52.0907&longitude=5.1214"));
        assert!(url.contains("models=knmi_seamless"));
    }
}
